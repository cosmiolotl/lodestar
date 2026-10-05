//! Just enough of the Minecraft protocol to get a player through login.

use std::io::{self, Read, Write};

use aes::Aes128;
use aes::cipher::generic_array::GenericArray;
use aes::cipher::{BlockEncrypt, KeyInit};
use bytes::{Buf, Bytes, BytesMut};
use flate2::Compression;
use flate2::read::ZlibDecoder;
use flate2::write::ZlibEncoder;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

/// The largest frame the vanilla protocol allows.
pub const MAX_FRAME: usize = (1 << 21) - 1;
/// The largest packet the vanilla protocol allows once decompressed.
const MAX_UNCOMPRESSED: usize = 1 << 23;

fn invalid(msg: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, msg)
}

pub fn put_varint(out: &mut Vec<u8>, value: i32) {
    let mut v = value as u32;
    loop {
        if v & !0x7f == 0 {
            out.push(v as u8);
            return;
        }
        out.push((v & 0x7f) as u8 | 0x80);
        v >>= 7;
    }
}

pub fn put_string(out: &mut Vec<u8>, s: &str) {
    put_varint(out, s.len() as i32);
    out.extend_from_slice(s.as_bytes());
}

pub fn put_byte_array(out: &mut Vec<u8>, b: &[u8]) {
    put_varint(out, b.len() as i32);
    out.extend_from_slice(b);
}

pub fn get_varint(b: &mut Bytes) -> io::Result<i32> {
    let mut value = 0u32;
    for shift in (0..35).step_by(7) {
        if !b.has_remaining() {
            return Err(invalid("truncated varint"));
        }
        let byte = b.get_u8();
        value |= u32::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 {
            return Ok(value as i32);
        }
    }
    Err(invalid("varint is too long"))
}

pub fn get_byte_array(b: &mut Bytes, max: usize) -> io::Result<Bytes> {
    let len = get_varint(b)?;
    if len < 0 || len as usize > max || len as usize > b.remaining() {
        return Err(invalid("bad byte array length"));
    }
    Ok(b.split_to(len as usize))
}

/// Reads a string of at most `max` bytes.
pub fn get_string(b: &mut Bytes, max: usize) -> io::Result<String> {
    let raw = get_byte_array(b, max)?;
    String::from_utf8(raw.to_vec()).map_err(|_| invalid("string is not valid UTF-8"))
}

pub fn get_u16(b: &mut Bytes) -> io::Result<u16> {
    if b.remaining() < 2 {
        return Err(invalid("truncated packet"));
    }
    Ok(b.get_u16())
}

pub fn get_i64(b: &mut Bytes) -> io::Result<i64> {
    if b.remaining() < 8 {
        return Err(invalid("truncated packet"));
    }
    Ok(b.get_i64())
}

pub fn get_u128(b: &mut Bytes) -> io::Result<u128> {
    if b.remaining() < 16 {
        return Err(invalid("truncated packet"));
    }
    Ok(b.get_u128())
}

/// AES-128 in CFB8 mode, the stream cipher Minecraft uses. One direction only.
pub struct Cfb8 {
    aes: Aes128,
    register: [u8; 16],
}

impl Cfb8 {
    /// Minecraft uses the shared secret as both key and IV.
    pub fn new(secret: &[u8; 16]) -> Cfb8 {
        Cfb8::with_iv(secret, secret)
    }

    fn with_iv(key: &[u8; 16], iv: &[u8; 16]) -> Cfb8 {
        Cfb8 {
            aes: Aes128::new(GenericArray::from_slice(key)),
            register: *iv,
        }
    }

    fn keystream_byte(&self) -> u8 {
        let mut block = GenericArray::clone_from_slice(&self.register);
        self.aes.encrypt_block(&mut block);
        block[0]
    }

    fn shift_in(&mut self, ciphertext: u8) {
        self.register.copy_within(1.., 0);
        self.register[15] = ciphertext;
    }

    pub fn encrypt(&mut self, data: &mut [u8]) {
        for byte in data {
            *byte ^= self.keystream_byte();
            self.shift_in(*byte);
        }
    }

    pub fn decrypt(&mut self, data: &mut [u8]) {
        for byte in data {
            let ciphertext = *byte;
            *byte ^= self.keystream_byte();
            self.shift_in(ciphertext);
        }
    }
}

/// A Minecraft connection in its packet-at-a-time phase.
pub struct Conn {
    stream: TcpStream,
    /// Received bytes, already decrypted, not yet parsed.
    rbuf: BytesMut,
    max_frame: usize,
    decrypt: Option<Cfb8>,
    encrypt: Option<Cfb8>,
    compression: Option<usize>,
}

/// What is left of a [`Conn`] once it switches to passing bytes through.
pub struct Parts {
    pub stream: TcpStream,
    /// Bytes that were read ahead of the last parsed packet.
    pub leftover: BytesMut,
    pub decrypt: Option<Cfb8>,
    pub encrypt: Option<Cfb8>,
}

impl Conn {
    /// `max_frame` bounds how much an unauthenticated peer can make us buffer.
    pub fn new(stream: TcpStream, max_frame: usize) -> Conn {
        Conn {
            stream,
            rbuf: BytesMut::new(),
            max_frame,
            decrypt: None,
            encrypt: None,
            compression: None,
        }
    }

    pub fn enable_encryption(&mut self, secret: &[u8; 16]) {
        self.decrypt = Some(Cfb8::new(secret));
        self.encrypt = Some(Cfb8::new(secret));
    }

    /// Switches both directions to the compressed packet format. A negative
    /// threshold turns compression off, as it does in vanilla.
    pub fn set_compression(&mut self, threshold: i32) {
        self.compression = usize::try_from(threshold).ok();
    }

    pub fn into_parts(self) -> Parts {
        Parts {
            stream: self.stream,
            leftover: self.rbuf,
            decrypt: self.decrypt,
            encrypt: self.encrypt,
        }
    }

    /// Returns the frame length and the size of its varint prefix, once the
    /// whole prefix has arrived.
    fn frame_header(&self) -> io::Result<Option<(usize, usize)>> {
        let mut len = 0usize;
        for (i, byte) in self.rbuf.iter().take(3).enumerate() {
            len |= usize::from(byte & 0x7f) << (7 * i);
            if byte & 0x80 == 0 {
                return Ok(Some((len, i + 1)));
            }
        }
        if self.rbuf.len() >= 3 {
            Err(invalid("frame length is too long"))
        } else {
            Ok(None)
        }
    }

    async fn read_frame(&mut self) -> io::Result<Bytes> {
        loop {
            if let Some((len, header)) = self.frame_header()? {
                if len == 0 || len > self.max_frame {
                    return Err(invalid("frame length out of range"));
                }
                if self.rbuf.len() >= header + len {
                    self.rbuf.advance(header);
                    return Ok(self.rbuf.split_to(len).freeze());
                }
            }
            let start = self.rbuf.len();
            if self.stream.read_buf(&mut self.rbuf).await? == 0 {
                return Err(io::ErrorKind::UnexpectedEof.into());
            }
            if let Some(cipher) = &mut self.decrypt {
                cipher.decrypt(&mut self.rbuf[start..]);
            }
        }
    }

    /// Reads one packet and returns its id and payload.
    pub async fn read_packet(&mut self) -> io::Result<(i32, Bytes)> {
        let mut body = self.read_frame().await?;
        if self.compression.is_some() {
            let uncompressed = get_varint(&mut body)? as usize;
            if uncompressed > MAX_UNCOMPRESSED {
                return Err(invalid("compressed packet is too large"));
            }
            if uncompressed != 0 {
                let mut inflated = Vec::with_capacity(uncompressed);
                // Read one byte past the claimed size so a lying header is caught.
                ZlibDecoder::new(&body[..])
                    .take(uncompressed as u64 + 1)
                    .read_to_end(&mut inflated)?;
                if inflated.len() != uncompressed {
                    return Err(invalid("compressed packet has the wrong size"));
                }
                body = Bytes::from(inflated);
            }
        }
        let id = get_varint(&mut body)?;
        Ok((id, body))
    }

    pub async fn write_packet(&mut self, id: i32, payload: &[u8]) -> io::Result<()> {
        let mut body = Vec::with_capacity(payload.len() + 5);
        put_varint(&mut body, id);
        body.extend_from_slice(payload);

        let mut frame = Vec::with_capacity(body.len() + 8);
        match self.compression {
            None => {
                put_varint(&mut frame, body.len() as i32);
                frame.extend_from_slice(&body);
            }
            Some(threshold) if body.len() >= threshold => {
                let mut rest = Vec::new();
                put_varint(&mut rest, body.len() as i32);
                let mut encoder = ZlibEncoder::new(rest, Compression::default());
                encoder.write_all(&body)?;
                let rest = encoder.finish()?;
                put_varint(&mut frame, rest.len() as i32);
                frame.extend_from_slice(&rest);
            }
            Some(_) => {
                put_varint(&mut frame, body.len() as i32 + 1);
                frame.push(0);
                frame.extend_from_slice(&body);
            }
        }
        if let Some(cipher) = &mut self.encrypt {
            cipher.encrypt(&mut frame);
        }
        self.stream.write_all(&frame).await
    }
}
