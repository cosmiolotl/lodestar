//! The parts of Minecraft 26.3's protocol that the scripted client speaks.
//!
//! Play packet ids follow the registration order in `GameProtocols`;
//! clientbound ones count a bundle packet at 0x00.

use std::io;

use bytes::{Buf, Bytes};

pub const PROTOCOL: i32 = 777;

pub mod login {
    pub const C_DISCONNECT: i32 = 0x00;
    pub const C_ENCRYPTION_REQUEST: i32 = 0x01;
    pub const C_LOGIN_FINISHED: i32 = 0x02;
    pub const C_COMPRESSION: i32 = 0x03;
    pub const C_CUSTOM_QUERY: i32 = 0x04;
    pub const C_COOKIE_REQUEST: i32 = 0x05;

    pub const S_HELLO: i32 = 0x00;
    pub const S_CUSTOM_QUERY_ANSWER: i32 = 0x02;
    pub const S_LOGIN_ACKNOWLEDGED: i32 = 0x03;
    pub const S_COOKIE_RESPONSE: i32 = 0x04;
}

pub mod config {
    pub const C_DISCONNECT: i32 = 0x02;
    pub const C_FINISH: i32 = 0x03;
    pub const C_KEEP_ALIVE: i32 = 0x04;
    pub const C_PING: i32 = 0x05;
    pub const C_SELECT_KNOWN_PACKS: i32 = 0x0F;

    pub const S_CLIENT_INFORMATION: i32 = 0x00;
    pub const S_FINISH: i32 = 0x03;
    pub const S_KEEP_ALIVE: i32 = 0x04;
    pub const S_PONG: i32 = 0x05;
    pub const S_SELECT_KNOWN_PACKS: i32 = 0x07;
}

pub mod play {
    pub const C_ADD_ENTITY: i32 = 0x01;
    pub const C_BLOCK_UPDATE: i32 = 0x08;
    pub const C_CHUNK_BATCH_FINISHED: i32 = 0x0B;
    pub const C_DISCONNECT: i32 = 0x20;
    pub const C_DISGUISED_CHAT: i32 = 0x21;
    pub const C_ENTITY_POSITION_SYNC: i32 = 0x23;
    pub const C_FORGET_LEVEL_CHUNK: i32 = 0x26;
    pub const C_KEEP_ALIVE: i32 = 0x2D;
    pub const C_LEVEL_CHUNK_WITH_LIGHT: i32 = 0x2E;
    pub const C_LOGIN: i32 = 0x32;
    pub const C_MOVE_ENTITY_POS: i32 = 0x36;
    pub const C_MOVE_ENTITY_POS_ROT: i32 = 0x37;
    pub const C_MOVE_ENTITY_ROT: i32 = 0x39;
    pub const C_PING: i32 = 0x3E;
    pub const C_PLAYER_CHAT: i32 = 0x42;
    pub const C_PLAYER_POSITION: i32 = 0x49;
    pub const C_REMOVE_ENTITIES: i32 = 0x4E;
    pub const C_RESPAWN: i32 = 0x54;
    pub const C_ROTATE_HEAD: i32 = 0x55;
    pub const C_SECTION_BLOCKS_UPDATE: i32 = 0x56;
    pub const C_SET_ENTITY_DATA: i32 = 0x65;
    pub const C_SET_ENTITY_MOTION: i32 = 0x67;
    pub const C_SET_HEALTH: i32 = 0x6A;
    pub const C_START_CONFIGURATION: i32 = 0x78;
    pub const C_SYSTEM_CHAT: i32 = 0x7C;

    pub const S_ACCEPT_TELEPORTATION: i32 = 0x00;
    pub const S_ATTACK: i32 = 0x01;
    pub const S_CHAT: i32 = 0x09;
    pub const S_CHUNK_BATCH_RECEIVED: i32 = 0x0B;
    pub const S_CLIENT_COMMAND: i32 = 0x0C;
    pub const S_CLIENT_TICK_END: i32 = 0x0D;
    pub const S_KEEP_ALIVE: i32 = 0x1C;
    pub const S_MOVE_PLAYER_POS: i32 = 0x1E;
    pub const S_PLAYER_ACTION: i32 = 0x29;
    pub const S_PLAYER_LOADED: i32 = 0x2C;
    pub const S_PONG: i32 = 0x2D;
    pub const S_USE_ITEM_ON: i32 = 0x42;
}

/// `minecraft:player` in the entity type registry.
pub const PLAYER_TYPE: i32 = 159;

fn need(b: &Bytes, n: usize) -> io::Result<()> {
    if b.remaining() < n {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "truncated packet"));
    }
    Ok(())
}

pub fn get_f32(b: &mut Bytes) -> io::Result<f32> {
    need(b, 4)?;
    Ok(b.get_f32())
}

pub fn get_f64(b: &mut Bytes) -> io::Result<f64> {
    need(b, 8)?;
    Ok(b.get_f64())
}

pub fn get_i32(b: &mut Bytes) -> io::Result<i32> {
    need(b, 4)?;
    Ok(b.get_i32())
}

pub fn get_i64(b: &mut Bytes) -> io::Result<i64> {
    need(b, 8)?;
    Ok(b.get_i64())
}

pub fn get_u128(b: &mut Bytes) -> io::Result<u128> {
    need(b, 16)?;
    Ok(b.get_u128())
}

pub fn get_varlong(b: &mut Bytes) -> io::Result<i64> {
    let mut value = 0i64;
    for i in 0..10 {
        need(b, 1)?;
        let byte = b.get_u8();
        value |= i64::from(byte & 0x7f) << (7 * i);
        if byte & 0x80 == 0 {
            return Ok(value);
        }
    }
    Err(io::Error::new(io::ErrorKind::InvalidData, "varlong is too long"))
}

/// A block position packed into a long, as the protocol sends it.
pub fn pack_block_pos(x: i32, y: i32, z: i32) -> i64 {
    (i64::from(x) & 0x3FF_FFFF) << 38 | (i64::from(z) & 0x3FF_FFFF) << 12 | (i64::from(y) & 0xFFF)
}

pub fn unpack_block_pos(v: i64) -> (i32, i32, i32) {
    ((v >> 38) as i32, (v << 52 >> 52) as i32, (v << 26 >> 38) as i32)
}
