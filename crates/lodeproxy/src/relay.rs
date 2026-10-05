//! Relays a logged-in player between their client and their node, and moves
//! them to another node when the coordinator says so.
//!
//! Bytes are passed through as they are, but the relay keeps track of where
//! each packet ends, which is all it needs to know of the play protocol: the
//! frame layout is the same in every release. That lets it:
//!
//! - stop passing on what the client sends between two packets, and say how
//!   many packets the node has been sent (*freezing*);
//! - log the player in to a second node in the background, presenting the
//!   token lodestar gave out, and keep that connection waiting;
//! - once the player has moved, pass on everything the old node sent before
//!   it hung up, then switch both directions to the new connection.
//!
//! See `crates/lodestar/src/state/handoff.rs` for the steps of a move.

use std::net::IpAddr;
use std::sync::Arc;
use std::time::Duration;

use bytes::{Buf, BytesMut};
use lode_protocol::Message;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;
use tracing::{debug, info, warn};

use crate::auth::Profile;
use crate::mc::{Cfb8, Parts};
use crate::session::{self, NodeLogin, Proxy};

/// The largest frame either side may send.
const MAX_FRAME: usize = (1 << 21) - 1;
/// How much of what the client sends is held while it is frozen, before the
/// proxy stops reading from it.
const FROZEN_BUFFER: usize = 4 * 1024 * 1024;
/// How long to wait for word of the move once the old node has hung up.
const SWITCH_TIMEOUT: Duration = Duration::from_secs(15);

/// What lodestar asks of a player's relay.
#[derive(Debug)]
pub enum Control {
    Dial { token: u64, addr: String },
    Freeze,
    Switch,
    Cancel,
}

/// Where each packet of a stream of frames ends.
#[derive(Default)]
struct Frames {
    /// The length prefix read so far of the next frame.
    header: u32,
    header_bytes: u32,
    /// What is left of the frame being passed through.
    remaining: usize,
}

impl Frames {
    fn at_boundary(&self) -> bool {
        self.remaining == 0 && self.header_bytes == 0
    }

    /// Takes in a stretch of the stream, and returns how many bytes of it end
    /// on the last frame boundary within it and how many frames that is.
    fn feed(&mut self, data: &[u8]) -> std::io::Result<(usize, u64)> {
        let (mut at, mut end, mut frames) = (0, 0, 0);
        while at < data.len() {
            if self.remaining > 0 {
                let take = self.remaining.min(data.len() - at);
                self.remaining -= take;
                at += take;
                if self.remaining == 0 {
                    end = at;
                    frames += 1;
                }
                continue;
            }
            let byte = data[at];
            at += 1;
            self.header |= u32::from(byte & 0x7f) << (7 * self.header_bytes);
            self.header_bytes += 1;
            if byte & 0x80 != 0 {
                if self.header_bytes == 3 {
                    return Err(invalid("frame length is too long"));
                }
                continue;
            }
            let len = self.header as usize;
            self.header = 0;
            self.header_bytes = 0;
            if len == 0 || len > MAX_FRAME {
                return Err(invalid("frame length out of range"));
            }
            self.remaining = len;
        }
        Ok((end, frames))
    }
}

fn invalid(msg: &'static str) -> std::io::Error {
    std::io::Error::new(std::io::ErrorKind::InvalidData, msg)
}

enum Up {
    /// Stop passing frames on, and answer with how many have been.
    Freeze(oneshot::Sender<u64>),
    Resume,
    /// Carry on, to the player's new node, which has been sent this many
    /// packets while the player was logged in there.
    Retarget(OwnedWriteHalf, u64),
}

enum Down {
    /// Once the current node hangs up, carry on with this one.
    Then(OwnedReadHalf, BytesMut),
    /// The node hung up for good.
    Stop,
}

enum Event {
    /// The client hung up, or the connection to it failed.
    ClientGone(std::io::Result<()>),
    /// The player's node hung up between two packets, with no other node to
    /// carry on with yet.
    NodeHungUp,
    /// Relaying to the client failed, or the node hung up mid-packet.
    DownGone(std::io::Result<()>),
    Dialed(anyhow::Result<Box<NodeLogin>>),
}

/// Passes frames from the client to its node.
async fn upstream(
    mut read: OwnedReadHalf,
    mut decrypt: Option<Cfb8>,
    mut buffer: BytesMut,
    mut sink: OwnedWriteHalf,
    // The frames written to the node, from its login on, and the whole ones
    // in the buffer, which will be.
    mut counted: u64,
    mut commands: mpsc::Receiver<Up>,
) -> std::io::Result<()> {
    let mut frames = Frames::default();
    // What of `buffer` has been through `frames` but not been written.
    let mut scanned = 0;
    let mut whole = 0;
    let mut frozen = false;
    loop {
        if scanned < buffer.len() {
            let (end, count) = frames.feed(&buffer[scanned..])?;
            if count > 0 {
                whole = scanned + end;
            }
            scanned = buffer.len();
            counted += count;
        }
        if !frozen && whole > 0 {
            sink.write_all(&buffer[..whole]).await?;
            buffer.advance(whole);
            scanned -= whole;
            whole = 0;
        }
        let room = !frozen || buffer.len() < FROZEN_BUFFER;
        tokio::select! {
            command = commands.recv() => match command {
                Some(Up::Freeze(reply)) => {
                    frozen = true;
                    // Frames counted but still in the buffer have not been written.
                    let held = frames_in(&buffer[..whole]);
                    let _ = reply.send(counted - held);
                }
                Some(Up::Resume) => frozen = false,
                Some(Up::Retarget(new, logged_in)) => {
                    sink = new;
                    // What is held goes to the new node.
                    counted = logged_in + frames_in(&buffer[..whole]);
                    frozen = false;
                }
                None => return Ok(()),
            },
            read_bytes = read.read_buf(&mut buffer), if room => {
                let start = buffer.len() - read_bytes?;
                if start == buffer.len() {
                    let _ = sink.shutdown().await;
                    return Ok(());
                }
                if let Some(cipher) = &mut decrypt {
                    cipher.decrypt(&mut buffer[start..]);
                }
            }
        }
    }
}

/// How many whole frames a stretch that starts and ends on frame boundaries holds.
fn frames_in(data: &[u8]) -> u64 {
    let mut frames = Frames::default();
    frames.feed(data).map_or(0, |(_, count)| count)
}

/// Passes what the player's node sends on to the client.
async fn downstream(
    mut read: OwnedReadHalf,
    leftover: BytesMut,
    mut write: OwnedWriteHalf,
    mut encrypt: Option<Cfb8>,
    mut commands: mpsc::Receiver<Down>,
    events: mpsc::Sender<Event>,
) -> std::io::Result<()> {
    let mut frames = Frames::default();
    let mut next: Option<(OwnedReadHalf, BytesMut)> = None;
    let mut pending = leftover.to_vec();
    let mut buf = vec![0u8; 32 * 1024];
    loop {
        if !pending.is_empty() {
            frames.feed(&pending)?;
            if let Some(cipher) = &mut encrypt {
                cipher.encrypt(&mut pending);
            }
            write.write_all(&pending).await?;
            pending.clear();
        }
        tokio::select! {
            command = commands.recv() => match command {
                Some(Down::Then(new, leftover)) => next = Some((new, leftover)),
                Some(Down::Stop) | None => return Ok(()),
            },
            n = read.read(&mut buf) => {
                let n = n?;
                if n > 0 {
                    pending.extend_from_slice(&buf[..n]);
                    continue;
                }
                if !frames.at_boundary() {
                    return Err(invalid("the node hung up in the middle of a packet"));
                }
                if next.is_none() {
                    let _ = events.send(Event::NodeHungUp).await;
                    match commands.recv().await {
                        Some(Down::Then(new, leftover)) => next = Some((new, leftover)),
                        Some(Down::Stop) | None => {
                            let _ = write.shutdown().await;
                            return Ok(());
                        }
                    }
                }
                let (new, leftover) = next.take().expect("set above");
                read = new;
                pending = leftover.to_vec();
            }
        }
    }
}

struct AbortOnDrop(JoinHandle<()>);

impl Drop for AbortOnDrop {
    fn drop(&mut self) {
        self.0.abort();
    }
}

/// A move under way, as far as this relay is concerned.
enum Move {
    Dialing { token: u64, _task: AbortOnDrop },
    Dialed(Box<NodeLogin>),
}

pub struct Session {
    pub client: Parts,
    pub backend: NodeLogin,
    pub profile: Profile,
    pub peer: IpAddr,
    pub protocol: i32,
}

/// Relays a player until they or their node hang up.
pub async fn run(proxy: Arc<Proxy>, session: Session) -> anyhow::Result<()> {
    let Session {
        client,
        backend,
        profile,
        peer,
        protocol,
    } = session;
    let uuid = profile.uuid;
    let (_registration, mut control) = proxy.star.register(uuid);
    let compression = backend.compression;
    let (events_tx, mut events) = mpsc::channel(16);

    let backend_parts = backend.conn.into_parts();
    let (client_read, client_write) = client.stream.into_split();
    let (backend_read, backend_write) = backend_parts.stream.into_split();
    let (up_tx, up_rx) = mpsc::channel(4);
    let (down_tx, down_rx) = mpsc::channel(4);
    let up = upstream(
        client_read,
        client.decrypt,
        client.leftover,
        backend_write,
        backend.frames,
        up_rx,
    );
    let tx = events_tx.clone();
    let _up = AbortOnDrop(tokio::spawn(async move {
        let _ = tx.send(Event::ClientGone(up.await)).await;
    }));
    let down = downstream(
        backend_read,
        backend_parts.leftover,
        client_write,
        client.encrypt,
        down_rx,
        events_tx.clone(),
    );
    let tx = events_tx.clone();
    let _down = AbortOnDrop(tokio::spawn(async move {
        let _ = tx.send(Event::DownGone(down.await)).await;
    }));

    let mut moving: Option<Move> = None;
    let mut frozen = false;
    // Set once the node has hung up while the player may be moving.
    let mut hung_up_at: Option<tokio::time::Instant> = None;
    loop {
        let deadline = async {
            match hung_up_at {
                Some(at) => tokio::time::sleep_until(at + SWITCH_TIMEOUT).await,
                None => std::future::pending().await,
            }
        };
        tokio::select! {
            () = deadline => {
                warn!("{} was left with no node to relay to", profile.name);
                return Ok(());
            }
            command = control.recv() => {
                let Some(command) = command else { return Ok(()) };
                debug!("{}: lodestar says {command:?}", profile.name);
                match command {
                    Control::Dial { token, addr } => {
                        let proxy = proxy.clone();
                        let profile = profile.clone();
                        let tx = events_tx.clone();
                        let task = tokio::spawn(async move {
                            let dialed = session::dial_for_handoff(&proxy, peer, protocol, &profile, &addr, token).await;
                            let _ = tx.send(Event::Dialed(dialed.map(Box::new))).await;
                        });
                        moving = Some(Move::Dialing { token, _task: AbortOnDrop(task) });
                    }
                    Control::Freeze => {
                        let (reply, frames) = oneshot::channel();
                        if up_tx.send(Up::Freeze(reply)).await.is_err() {
                            return Ok(());
                        }
                        let Ok(frames) = frames.await else { return Ok(()) };
                        frozen = true;
                        proxy.star.notify(Message::HandoffFrozen { uuid, frames });
                    }
                    Control::Switch => {
                        let Some(Move::Dialed(login)) = moving.take() else {
                            warn!("told to move {} with no connection to move them to", profile.name);
                            return Ok(());
                        };
                        let logged_in = login.frames;
                        let parts = login.conn.into_parts();
                        let (read, write) = parts.stream.into_split();
                        if down_tx.send(Down::Then(read, parts.leftover)).await.is_err()
                            || up_tx.send(Up::Retarget(write, logged_in)).await.is_err()
                        {
                            return Ok(());
                        }
                        frozen = false;
                        hung_up_at = None;
                        info!("{} is relayed to their new node", profile.name);
                    }
                    Control::Cancel => {
                        moving = None;
                        if frozen {
                            frozen = false;
                            let _ = up_tx.send(Up::Resume).await;
                        }
                        // The old node let go of the player after all.
                        if hung_up_at.is_some() {
                            return Ok(());
                        }
                    }
                }
            }
            event = events.recv() => match event.expect("the session holds a sender") {
                Event::ClientGone(result) => {
                    if let Err(e) = result {
                        debug!("{}: client connection failed: {e}", profile.name);
                    }
                    return Ok(());
                }
                Event::DownGone(result) => {
                    if let Err(e) = result {
                        warn!("{}: relaying from their node failed: {e}", profile.name);
                    }
                    return Ok(());
                }
                Event::NodeHungUp => {
                    if !frozen {
                        let _ = down_tx.send(Down::Stop).await;
                        return Ok(());
                    }
                    // The old node lets go of a player it is moving by
                    // hanging up; the word to switch may be on its way.
                    hung_up_at = Some(tokio::time::Instant::now());
                }
                Event::Dialed(result) => {
                    let token = match &moving {
                        Some(Move::Dialing { token, .. }) => *token,
                        _ => continue,
                    };
                    match result {
                        Ok(login) if login.compression == compression => {
                            debug!("{} is waiting on their next node (token {token:x})", profile.name);
                            moving = Some(Move::Dialed(login));
                        }
                        Ok(_) => {
                            warn!(
                                "cannot move {}: the nodes compress packets differently; give them the same network-compression-threshold",
                                profile.name
                            );
                            moving = None;
                            proxy.star.notify(Message::HandoffFailed { uuid });
                        }
                        Err(e) => {
                            warn!("cannot move {}: {e:#}", profile.name);
                            moving = None;
                            proxy.star.notify(Message::HandoffFailed { uuid });
                        }
                    }
                }
            },
        }
    }
}
