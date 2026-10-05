//! Hands a player's authenticated identity to the node they are sent to.
//!
//! Nodes run without authentication of their own, so they must be told who is
//! connecting by something they can trust. This is Velocity's "modern
//! forwarding" (version 1): during login the node asks on the
//! `velocity:player_info` channel, and the proxy answers with the player's
//! address and profile, signed with a secret both sides share.

use std::net::IpAddr;

use hmac::{Hmac, Mac};
use sha2::Sha256;

use crate::auth::Profile;
use crate::mc::{put_string, put_varint};

pub const CHANNEL: &str = "velocity:player_info";
const VERSION: i32 = 1;

pub fn player_info(secret: &[u8], addr: IpAddr, profile: &Profile) -> Vec<u8> {
    let mut payload = Vec::new();
    put_varint(&mut payload, VERSION);
    put_string(&mut payload, &addr.to_string());
    payload.extend_from_slice(&profile.uuid.to_be_bytes());
    put_string(&mut payload, &profile.name);
    put_varint(&mut payload, profile.properties.len() as i32);
    for property in &profile.properties {
        put_string(&mut payload, &property.name);
        put_string(&mut payload, &property.value);
        match &property.signature {
            Some(signature) => {
                payload.push(1);
                put_string(&mut payload, signature);
            }
            None => payload.push(0),
        }
    }

    let mut mac = Hmac::<Sha256>::new_from_slice(secret).expect("HMAC accepts keys of any length");
    mac.update(&payload);
    let mut signed = mac.finalize().into_bytes().to_vec();
    signed.extend_from_slice(&payload);
    signed
}
