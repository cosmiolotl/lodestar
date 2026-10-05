//! Player authentication against Mojang's session service.

use anyhow::Context;
use md5::Md5;
use rsa::pkcs8::EncodePublicKey;
use rsa::{Pkcs1v15Encrypt, RsaPrivateKey};
use serde::Deserialize;
use sha1::{Digest, Sha1};

const HAS_JOINED: &str = "https://sessionserver.mojang.com/session/minecraft/hasJoined";

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct Property {
    pub name: String,
    pub value: String,
    pub signature: Option<String>,
}

/// An authenticated identity.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Profile {
    pub uuid: u128,
    pub name: String,
    /// Carries the player's skin.
    pub properties: Vec<Property>,
}

/// The key pair clients encrypt their shared secret to.
pub struct Keys {
    private: RsaPrivateKey,
    pub public_der: Vec<u8>,
}

impl Keys {
    pub fn generate() -> anyhow::Result<Keys> {
        // 1024 bits is what vanilla servers use; the key only protects the
        // exchange of a per-connection secret and is regenerated every start.
        let private = RsaPrivateKey::new(&mut rand::thread_rng(), 1024)?;
        let public_der = private.to_public_key().to_public_key_der()?.into_vec();
        Ok(Keys {
            private,
            public_der,
        })
    }

    pub fn decrypt(&self, data: &[u8]) -> anyhow::Result<Vec<u8>> {
        Ok(self.private.decrypt(Pkcs1v15Encrypt, data)?)
    }
}

/// Minecraft's server hash: a SHA-1 digest printed as a signed hexadecimal number.
pub fn server_hash(shared_secret: &[u8], public_der: &[u8]) -> String {
    let mut hasher = Sha1::new();
    hasher.update(shared_secret);
    hasher.update(public_der);
    signed_hex(hasher.finalize().into())
}

fn signed_hex(mut digest: [u8; 20]) -> String {
    let negative = digest[0] & 0x80 != 0;
    if negative {
        // Two's complement negation: invert, then add one.
        let mut carry = true;
        for byte in digest.iter_mut().rev() {
            *byte = !*byte;
            if carry {
                (*byte, carry) = byte.overflowing_add(1);
            }
        }
    }
    let hex: String = digest.iter().map(|b| format!("{b:02x}")).collect();
    let hex = hex.trim_start_matches('0');
    format!(
        "{}{}",
        if negative { "-" } else { "" },
        if hex.is_empty() { "0" } else { hex }
    )
}

/// Asks Mojang whether `name` has joined the server identified by `hash`.
/// Returns `None` if the player is not authenticated.
pub async fn has_joined(
    http: &reqwest::Client,
    name: &str,
    hash: &str,
) -> anyhow::Result<Option<Profile>> {
    #[derive(Deserialize)]
    struct Response {
        id: String,
        name: String,
        #[serde(default)]
        properties: Vec<Property>,
    }

    let response = http
        .get(HAS_JOINED)
        .query(&[("username", name), ("serverId", hash)])
        .send()
        .await
        .context("contacting the session server")?;
    if response.status() != reqwest::StatusCode::OK {
        return Ok(None);
    }
    let body: Response = response
        .json()
        .await
        .context("reading the session server's answer")?;
    let uuid = u128::from_str_radix(&body.id, 16).context("session server sent a malformed id")?;
    Ok(Some(Profile {
        uuid,
        name: body.name,
        properties: body.properties,
    }))
}

/// The UUID vanilla assigns to `name` on a server that does not authenticate.
pub fn offline_uuid(name: &str) -> u128 {
    let mut digest: [u8; 16] = Md5::digest(format!("OfflinePlayer:{name}")).into();
    digest[6] = digest[6] & 0x0f | 0x30; // version 3
    digest[8] = digest[8] & 0x3f | 0x80; // IETF variant
    u128::from_be_bytes(digest)
}
