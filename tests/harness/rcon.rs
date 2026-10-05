//! A client for a server's remote console.
//!
//! The vanilla server reads each request with a single socket read, so a
//! request must arrive alone; it splits a long answer into pieces of 4096
//! characters. After a full piece, a request of an unknown type marks the end:
//! the server answers it only once it has sent the rest of the first answer.

use std::io::{self, Read, Write};
use std::net::TcpStream;
use std::time::Duration;

const TYPE_RESPONSE: i32 = 0;
const TYPE_COMMAND: i32 = 2;
const TYPE_LOGIN: i32 = 3;
const PIECE: usize = 4096;

pub struct Rcon {
    stream: TcpStream,
    next_id: i32,
}

impl Rcon {
    pub fn connect(port: u16, password: &str) -> io::Result<Rcon> {
        let stream = TcpStream::connect(("127.0.0.1", port))?;
        stream.set_nodelay(true)?;
        // Commands wait for the server thread, which can be busy saving.
        stream.set_read_timeout(Some(Duration::from_secs(120)))?;
        let mut rcon = Rcon { stream, next_id: 1 };
        let id = rcon.send(TYPE_LOGIN, password)?;
        let (answered, _) = rcon.receive()?;
        if answered != id {
            return Err(io::Error::new(io::ErrorKind::PermissionDenied, "RCON refused the password"));
        }
        Ok(rcon)
    }

    /// Runs a command and returns what the server answered.
    pub fn run(&mut self, command: &str) -> io::Result<String> {
        let id = self.send(TYPE_COMMAND, command)?;
        let (answered, mut text) = self.receive()?;
        if answered != id {
            return Err(io::Error::other(format!("RCON answered request {answered}, not {id}")));
        }
        if text.encode_utf16().count() >= PIECE {
            let marker = self.send(TYPE_RESPONSE, "")?;
            loop {
                let (answered, piece) = self.receive()?;
                if answered == marker {
                    break;
                }
                text.push_str(&piece);
            }
        }
        Ok(text)
    }

    fn send(&mut self, kind: i32, body: &str) -> io::Result<i32> {
        let id = self.next_id;
        self.next_id += 1;
        let mut packet = Vec::with_capacity(body.len() + 14);
        packet.extend_from_slice(&(body.len() as i32 + 10).to_le_bytes());
        packet.extend_from_slice(&id.to_le_bytes());
        packet.extend_from_slice(&kind.to_le_bytes());
        packet.extend_from_slice(body.as_bytes());
        packet.extend_from_slice(&[0, 0]);
        self.stream.write_all(&packet)?;
        Ok(id)
    }

    fn receive(&mut self) -> io::Result<(i32, String)> {
        let mut length = [0; 4];
        self.stream.read_exact(&mut length)?;
        let length = i32::from_le_bytes(length);
        if !(10..=PIECE as i32 * 4 + 10).contains(&length) {
            return Err(io::Error::other(format!("RCON packet of length {length}")));
        }
        let mut packet = vec![0; length as usize];
        self.stream.read_exact(&mut packet)?;
        let id = i32::from_le_bytes(packet[..4].try_into().unwrap());
        let body = &packet[8..packet.len() - 2];
        Ok((id, String::from_utf8_lossy(body).into_owned()))
    }
}
