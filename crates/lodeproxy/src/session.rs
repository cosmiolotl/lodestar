//! A player's connection, from handshake to the node they end up on.

use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, bail, ensure};
use bytes::Bytes;
use lode_protocol::PlacementOutcome;
use rand::RngCore;
use tokio::net::{TcpListener, TcpStream};
use tracing::{debug, info, warn};

use crate::auth::{self, Keys, Profile};
use crate::config::Config;
use crate::forwarding;
use crate::mc::{
    self, Conn, get_byte_array, get_i64, get_string, get_u16, get_u128, get_varint, put_byte_array,
    put_string, put_varint,
};
use crate::relay;
use crate::star::Star;

/// Time a connection gets to finish logging in, end to end.
const LOGIN_TIMEOUT: Duration = Duration::from_secs(30);
const BACKEND_CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
/// Nothing a client sends before it is authenticated comes close to this.
const CLIENT_MAX_FRAME: usize = 16 * 1024;

const INTENT_STATUS: i32 = 1;
const INTENT_LOGIN: i32 = 2;
const INTENT_TRANSFER: i32 = 3;

/// Packet ids of the login state. These are part of the stable core of the
/// protocol, unlike the ids used during play.
mod login {
    pub const C_DISCONNECT: i32 = 0x00;
    pub const C_ENCRYPTION_REQUEST: i32 = 0x01;
    pub const C_LOGIN_SUCCESS: i32 = 0x02;
    pub const C_SET_COMPRESSION: i32 = 0x03;
    pub const C_PLUGIN_REQUEST: i32 = 0x04;
    pub const C_COOKIE_REQUEST: i32 = 0x05;

    pub const S_LOGIN_START: i32 = 0x00;
    pub const S_ENCRYPTION_RESPONSE: i32 = 0x01;
    pub const S_PLUGIN_RESPONSE: i32 = 0x02;
    pub const S_LOGIN_ACKNOWLEDGED: i32 = 0x03;
    pub const S_COOKIE_RESPONSE: i32 = 0x04;
}

pub struct Proxy {
    pub config: Config,
    pub star: Star,
    /// Present when `online_mode` is on.
    pub keys: Option<Keys>,
    pub http: reqwest::Client,
}

impl Proxy {
    pub fn new(config: Config) -> anyhow::Result<Proxy> {
        let star = Star::connect(
            config.lodestar.clone(),
            config.token.clone(),
            config.name.clone(),
        );
        let keys = if config.online_mode {
            Some(Keys::generate()?)
        } else {
            None
        };
        let http = reqwest::Client::builder()
            .timeout(Duration::from_secs(10))
            .build()?;
        Ok(Proxy {
            config,
            star,
            keys,
            http,
        })
    }
}

/// The login channel a node asks on whether a login is the proxy moving a
/// player there from another node. The answer is the token lodestar gave out.
const HANDOFF_CHANNEL: &str = "lodecore:handoff";

/// A connection to a node that a player is logged in on.
pub struct NodeLogin {
    pub conn: Conn,
    /// How many packets the proxy has written to the node so far.
    pub frames: u64,
    /// The compression threshold the node set, if any.
    pub compression: Option<i32>,
}

/// Accepts players on `listener` until the task is cancelled.
pub async fn serve(listener: TcpListener, proxy: Arc<Proxy>) {
    loop {
        let (stream, peer) = match listener.accept().await {
            Ok(accepted) => accepted,
            Err(e) => {
                warn!("accept failed: {e}");
                continue;
            }
        };
        let proxy = proxy.clone();
        tokio::spawn(async move {
            if let Err(e) = handle(proxy, stream, peer).await {
                debug!("{peer}: {e:#}");
            }
        });
    }
}

async fn handle(proxy: Arc<Proxy>, stream: TcpStream, peer: SocketAddr) -> anyhow::Result<()> {
    stream.set_nodelay(true)?;
    let mut client = Conn::new(stream, CLIENT_MAX_FRAME);

    let logged_in = tokio::time::timeout(LOGIN_TIMEOUT, async {
        let (id, mut packet) = client.read_packet().await?;
        ensure!(id == 0, "expected a handshake");
        let protocol = get_varint(&mut packet)?;
        let _host = get_string(&mut packet, 255)?;
        let _port = get_u16(&mut packet)?;
        match get_varint(&mut packet)? {
            INTENT_STATUS => status(&proxy, &mut client, protocol).await.map(|()| None),
            INTENT_LOGIN | INTENT_TRANSFER => login(&proxy, &mut client, peer, protocol).await,
            other => bail!("unknown handshake intent {other}"),
        }
    })
    .await
    .context("login timed out")??;

    match logged_in {
        Some((backend, profile, protocol)) => {
            let session = relay::Session {
                client: client.into_parts(),
                backend,
                profile,
                peer: peer.ip(),
                protocol,
            };
            relay::run(proxy, session).await
        }
        None => Ok(()),
    }
}

async fn status(proxy: &Proxy, client: &mut Conn, protocol: i32) -> anyhow::Result<()> {
    loop {
        let (id, mut packet) = client.read_packet().await?;
        match id {
            0 => {
                let status = proxy.star.status().await.unwrap_or_default();
                let json = serde_json::json!({
                    // Echo the client's version: compatibility is decided by the nodes.
                    "version": { "name": "Lodestar", "protocol": protocol },
                    "players": { "max": status.capacity, "online": status.online },
                    "description": { "text": proxy.config.motd },
                });
                let mut out = Vec::new();
                put_string(&mut out, &json.to_string());
                client.write_packet(0, &out).await?;
            }
            1 => {
                let payload = get_i64(&mut packet)?;
                client.write_packet(1, &payload.to_be_bytes()).await?;
                return Ok(());
            }
            other => bail!("unexpected status packet {other}"),
        }
    }
}

/// Tells a client that is still logging in why it cannot join.
async fn refuse(client: &mut Conn, reason: &str) -> anyhow::Result<()> {
    let mut out = Vec::new();
    put_string(&mut out, &serde_json::json!({ "text": reason }).to_string());
    client.write_packet(login::C_DISCONNECT, &out).await?;
    Ok(())
}

/// Logs a player in. Returns the connection to their node once both sides are
/// ready to have bytes passed between them, or `None` if the player was refused.
async fn login(
    proxy: &Proxy,
    client: &mut Conn,
    peer: SocketAddr,
    protocol: i32,
) -> anyhow::Result<Option<(NodeLogin, Profile, i32)>> {
    let (id, mut packet) = client.read_packet().await?;
    ensure!(id == login::S_LOGIN_START, "expected login start");
    let name = get_string(&mut packet, 16)?;
    ensure!(
        !name.is_empty() && name.bytes().all(|b| b.is_ascii_graphic()),
        "invalid player name"
    );

    let profile = match &proxy.keys {
        Some(keys) => match authenticate(proxy, keys, client, &name).await? {
            Some(profile) => profile,
            None => {
                refuse(
                    client,
                    "Failed to verify your session. Try restarting your game.",
                )
                .await?;
                return Ok(None);
            }
        },
        None => Profile {
            uuid: auth::offline_uuid(&name),
            name,
            properties: Vec::new(),
        },
    };

    let addr = match proxy.star.place(profile.uuid, &profile.name).await {
        Some(PlacementOutcome::Node { addr, .. }) => addr,
        Some(PlacementOutcome::Denied { reason }) => {
            refuse(client, &reason).await?;
            return Ok(None);
        }
        None => {
            refuse(client, "The server is starting up. Try again in a moment.").await?;
            return Ok(None);
        }
    };

    match join_node(
        proxy,
        Some(client),
        peer.ip(),
        protocol,
        &profile,
        &addr,
        None,
    )
    .await
    {
        Ok(Some(backend)) => {
            info!("{} joined via {addr}", profile.name);
            Ok(Some((backend, profile, protocol)))
        }
        Ok(None) => Ok(None),
        Err(e) => {
            warn!("could not hand {} to node {addr}: {e:#}", profile.name);
            refuse(client, "Could not reach the server. Try again in a moment.").await?;
            Ok(None)
        }
    }
}

/// Runs the encryption handshake and checks the player with Mojang.
async fn authenticate(
    proxy: &Proxy,
    keys: &Keys,
    client: &mut Conn,
    name: &str,
) -> anyhow::Result<Option<Profile>> {
    let mut verify_token = [0u8; 4];
    rand::thread_rng().fill_bytes(&mut verify_token);

    let mut out = Vec::new();
    put_string(&mut out, ""); // server id, unused by modern clients
    put_byte_array(&mut out, &keys.public_der);
    put_byte_array(&mut out, &verify_token);
    out.push(1); // the client should authenticate with Mojang
    client
        .write_packet(login::C_ENCRYPTION_REQUEST, &out)
        .await?;

    let (id, mut packet) = client.read_packet().await?;
    ensure!(
        id == login::S_ENCRYPTION_RESPONSE,
        "expected an encryption response"
    );
    let secret = keys.decrypt(&get_byte_array(&mut packet, 256)?)?;
    let token = keys.decrypt(&get_byte_array(&mut packet, 256)?)?;
    ensure!(token == verify_token, "verify token mismatch");
    let secret: [u8; 16] = secret
        .try_into()
        .ok()
        .context("shared secret has the wrong length")?;
    client.enable_encryption(&secret);

    let hash = auth::server_hash(&secret, &keys.public_der);
    auth::has_joined(&proxy.http, name, &hash).await
}

/// Logs a player who is moving to another node in there as well. Their
/// client does not hear of it: the connection is kept waiting until the move.
pub async fn dial_for_handoff(
    proxy: &Proxy,
    peer: IpAddr,
    protocol: i32,
    profile: &Profile,
    addr: &str,
    token: u64,
) -> anyhow::Result<NodeLogin> {
    tokio::time::timeout(
        LOGIN_TIMEOUT,
        join_node(proxy, None, peer, protocol, profile, addr, Some(token)),
    )
    .await
    .context("logging in timed out")??
    .context("the node refused the player")
}

/// Opens a connection to a node and logs the player in there, relaying to the
/// client what it needs to see, if there is one. Returns `None` if the node
/// refused the player.
async fn join_node(
    proxy: &Proxy,
    mut client: Option<&mut Conn>,
    peer: IpAddr,
    protocol: i32,
    profile: &Profile,
    addr: &str,
    handoff: Option<u64>,
) -> anyhow::Result<Option<NodeLogin>> {
    let stream = tokio::time::timeout(BACKEND_CONNECT_TIMEOUT, TcpStream::connect(addr))
        .await
        .context("connecting timed out")??;
    stream.set_nodelay(true)?;
    let mut backend = Conn::new(stream, mc::MAX_FRAME);
    // Every packet written to the node counts: the node counts every one it reads.
    let mut frames = 0;
    let mut compression = None;

    let (host, port) = addr.rsplit_once(':').context("node address has no port")?;
    let mut out = Vec::new();
    put_varint(&mut out, protocol);
    put_string(&mut out, host);
    out.extend_from_slice(&port.parse::<u16>()?.to_be_bytes());
    put_varint(&mut out, INTENT_LOGIN);
    backend.write_packet(0, &out).await?;
    frames += 1;

    let mut out = Vec::new();
    put_string(&mut out, &profile.name);
    out.extend_from_slice(&profile.uuid.to_be_bytes());
    backend.write_packet(login::S_LOGIN_START, &out).await?;
    frames += 1;

    let mut forwarded = false;
    loop {
        let (id, mut packet) = backend.read_packet().await?;
        match id {
            login::C_DISCONNECT => {
                if let Some(client) = client.as_deref_mut() {
                    client.write_packet(login::C_DISCONNECT, &packet).await?;
                }
                return Ok(None);
            }
            login::C_ENCRYPTION_REQUEST => {
                bail!(
                    "node is in online mode; set online-mode=false and let the proxy authenticate"
                )
            }
            login::C_SET_COMPRESSION => {
                // Use the node's threshold towards the client too, so that
                // frames can later be passed through without being re-encoded.
                let threshold = get_varint(&mut Bytes::clone(&packet))?;
                if let Some(client) = client.as_deref_mut() {
                    client
                        .write_packet(login::C_SET_COMPRESSION, &packet)
                        .await?;
                    client.set_compression(threshold);
                }
                backend.set_compression(threshold);
                compression = Some(threshold);
            }
            login::C_PLUGIN_REQUEST => {
                let message_id = get_varint(&mut packet)?;
                let channel = get_string(&mut packet, 32767)?;
                let mut out = Vec::new();
                put_varint(&mut out, message_id);
                if channel == forwarding::CHANNEL {
                    let secret = proxy.config.forwarding_secret.as_bytes();
                    out.push(1);
                    out.extend_from_slice(&forwarding::player_info(secret, peer, profile));
                    forwarded = true;
                } else if let (HANDOFF_CHANNEL, Some(token)) = (channel.as_str(), handoff) {
                    out.push(1);
                    out.extend_from_slice(&token.to_be_bytes());
                } else {
                    out.push(0); // not understood
                }
                backend.write_packet(login::S_PLUGIN_RESPONSE, &out).await?;
                frames += 1;
            }
            login::C_COOKIE_REQUEST => {
                let key = get_string(&mut packet, 32767)?;
                let mut out = Vec::new();
                put_string(&mut out, &key);
                out.push(0); // no such cookie
                backend.write_packet(login::S_COOKIE_RESPONSE, &out).await?;
                frames += 1;
            }
            login::C_LOGIN_SUCCESS => {
                // A node that never asked who the player is would let them in
                // under whatever name they typed.
                ensure!(
                    forwarded,
                    "node did not ask for the player's identity; is the Lodecore mod installed?"
                );
                ensure!(
                    get_u128(&mut Bytes::clone(&packet))? == profile.uuid,
                    "node logged the player in under a different identity"
                );
                match client.as_deref_mut() {
                    Some(client) => client.write_packet(login::C_LOGIN_SUCCESS, &packet).await?,
                    // The client logged in long ago; the proxy ends the login
                    // for it, and the node waits for the player there.
                    None => {
                        backend
                            .write_packet(login::S_LOGIN_ACKNOWLEDGED, &[])
                            .await?;
                        frames += 1;
                    }
                }
                return Ok(Some(NodeLogin {
                    conn: backend,
                    frames,
                    compression,
                }));
            }
            other => bail!("unexpected login packet {other:#04x} from node"),
        }
    }
}
