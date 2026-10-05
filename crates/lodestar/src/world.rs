//! Durable world records. Names are encoded, never interpreted as filesystem paths.
use std::io::{self, Write};
use std::path::Path;

use bytes::{Buf, BufMut, Bytes, BytesMut};

pub const STORAGE: u8 = 240;
pub const READ: u8 = 0;
pub const WRITE: u8 = 1;
pub const JOURNAL: u8 = 2;
pub const REVISION: u8 = 3;
pub const EPOCH: u8 = 4;
pub const BIND: u8 = 5;
pub const SNAPSHOT: u8 = 6;

#[derive(Debug, Clone)]
pub struct Request {
    pub id: u64,
    pub operation: u8,
    pub dimension: String,
    pub kind: String,
    pub x: i32,
    pub z: i32,
    pub data: Bytes,
}

impl Request {
    pub fn decode(mut bytes: Bytes) -> io::Result<Self> {
        fn string(bytes: &mut Bytes) -> io::Result<String> {
            if bytes.remaining() < 2 {
                return Err(invalid());
            }
            let length = bytes.get_u16() as usize;
            if length > 256 || bytes.remaining() < length {
                return Err(invalid());
            }
            String::from_utf8(bytes.split_to(length).to_vec()).map_err(|_| invalid())
        }
        if bytes.remaining() < 10 || bytes.get_u8() != STORAGE {
            return Err(invalid());
        }
        let id = bytes.get_u64();
        let operation = bytes.get_u8();
        let dimension = string(&mut bytes)?;
        let kind = string(&mut bytes)?;
        if bytes.remaining() < 8 {
            return Err(invalid());
        }
        let x = bytes.get_i32();
        let z = bytes.get_i32();
        if operation > SNAPSHOT || (operation != WRITE && operation < BIND && !bytes.is_empty()) {
            return Err(invalid());
        }
        Ok(Self {
            id,
            operation,
            dimension,
            kind,
            x,
            z,
            data: bytes,
        })
    }
}

fn invalid() -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, "invalid world storage request")
}

pub fn response(id: u64, status: u8, data: &[u8]) -> Bytes {
    let mut bytes = BytesMut::with_capacity(10 + data.len());
    bytes.put_u8(STORAGE);
    bytes.put_u64(id);
    bytes.put_u8(status);
    bytes.extend_from_slice(data);
    bytes.freeze()
}

pub use crate::world_database::WorldStore;

pub fn atomic_write(path: &Path, data: &[u8]) -> io::Result<()> {
    let temporary = path.with_extension("tmp");
    let mut file = std::fs::File::create(&temporary)?;
    file.write_all(data)?;
    file.sync_all()?;
    drop(file);
    std::fs::rename(temporary, path)?;
    #[cfg(unix)]
    std::fs::File::open(path.parent().unwrap())?.sync_all()?;
    Ok(())
}
