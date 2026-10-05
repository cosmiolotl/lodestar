//! A scripted player: a Minecraft client without the game.
//!
//! It logs in (the proxy must be in offline mode), answers keep-alives,
//! teleports and chunk batches as a client does, respawns when it dies, and
//! on request walks back and forth, chats, attacks, and places or breaks
//! blocks. It keeps what a player would notice for the test to look at:
//!
//! - where the server put it, every time, which while it walks means the
//!   server disagreed with where it went;
//! - the entities and chunks it was shown, and anything out of place: a packet
//!   about an entity it was never shown, an entity or chunk shown twice, a
//!   chunk or entity taken away that it never had ([`Anomalies`]);
//! - every chat and system message, as text in which words can be looked for;
//! - the blocks it was told changed;
//! - why its connection ended, if it did.

use std::collections::{HashMap, HashSet};
use std::net::SocketAddr;
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{Context, bail};
use bytes::Bytes;
use lodeproxy::mc::{self, Conn, get_string, get_varint, put_string, put_varint};
use tokio::net::TcpStream;
use tokio::runtime::Runtime;
use tokio::sync::mpsc;

use crate::wire::{self, config, login, play};

/// How long after being put somewhere a walking client starts moving.
const SETTLE: Duration = Duration::from_secs(3);
/// Blocks per tick, a walking pace.
const STEP: f64 = 0.2;
/// How far either way of where it was put a walking client goes.
const REACH: f64 = 3.0;

pub(crate) fn runtime() -> &'static Runtime {
    static RUNTIME: OnceLock<Runtime> = OnceLock::new();
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("starting the clients' runtime")
    })
}

#[derive(Debug, Clone)]
pub struct Entity {
    pub uuid: u128,
    pub kind: i32,
}

/// What a client was sent that a client could not make sense of.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Anomalies {
    /// Packets about an entity the client was never shown.
    pub unknown_refs: u64,
    pub unknown_ref_ids: Vec<i32>,
    pub added_twice: u64,
    pub removed_unknown: u64,
    /// Chunks sent while the client had them, and the first of them.
    pub chunks_twice: u64,
    pub twice_chunks: Vec<(i32, i32)>,
    /// Chunks it was told to forget that it never had. The game itself does
    /// this when a chunk comes into view before it is ready to send and goes
    /// out of view again before it is sent, and a client ignores it, so it is
    /// kept but not counted as something out of place.
    pub forgot_unknown: u64,
}

impl Anomalies {
    /// The counts of what a player could notice, which is what a test compares.
    pub fn counts(&self) -> [u64; 4] {
        [self.unknown_refs, self.added_twice, self.removed_unknown, self.chunks_twice]
    }
}

#[derive(Debug, Clone)]
pub enum Event {
    /// The server put the client somewhere.
    Teleport { pos: [f64; 3], walking: bool },
    /// A chat or system message, as text.
    Chat(String),
    Died,
    Respawned,
}

#[derive(Debug, Default)]
pub struct State {
    pub entity_id: Option<i32>,
    pub position: Option<[f64; 3]>,
    /// Whether it has had its first chunks, and said it is in the world.
    pub loaded: bool,
    pub health: Option<f32>,
    pub entities: HashMap<i32, Entity>,
    pub chunks: HashSet<(i32, i32)>,
    /// The block state each block it was told about changed to last.
    pub blocks: HashMap<(i32, i32, i32), i32>,
    pub anomalies: Anomalies,
    pub events: Vec<(Instant, Event)>,
    pub chats_sent: Vec<(Instant, String)>,
    /// Why the connection ended, once it has.
    pub ended: Option<String>,
}

impl State {
    pub fn events_since(&self, since: Instant) -> impl Iterator<Item = &Event> {
        self.events.iter().filter(move |(at, _)| *at >= since).map(|(_, e)| e)
    }

    pub fn teleports_since(&self, since: Instant) -> Vec<[f64; 3]> {
        self.events_since(since)
            .filter_map(|e| match e {
                Event::Teleport { pos, .. } => Some(*pos),
                _ => None,
            })
            .collect()
    }

    /// Whether it was sent a message, since then, with all of these words in it.
    pub fn heard(&self, words: &[&str], since: Instant) -> bool {
        self.events_since(since).any(|e| match e {
            Event::Chat(text) => words.iter().all(|w| text.contains(w)),
            _ => false,
        })
    }

    /// The id of the entity it was shown with this UUID.
    pub fn sees(&self, uuid: u128) -> Option<i32> {
        self.entities.iter().find(|(_, e)| e.uuid == uuid).map(|(id, _)| *id)
    }

    /// The id of the player it was shown with this UUID.
    pub fn sees_player(&self, uuid: u128) -> Option<i32> {
        self.entities
            .iter()
            .find(|(_, e)| e.uuid == uuid && e.kind == wire::PLAYER_TYPE)
            .map(|(id, _)| *id)
    }

    fn touch(&mut self, id: i32) {
        if Some(id) != self.entity_id && !self.entities.contains_key(&id) {
            self.anomalies.unknown_refs += 1;
            if self.anomalies.unknown_ref_ids.len() < 20 {
                self.anomalies.unknown_ref_ids.push(id);
            }
        }
    }
}

enum Action {
    Walk(bool),
    Chat(String),
    Attack(i32),
    UseItemOn(i32, i32, i32),
    StartDestroy(i32, i32, i32),
    Quit,
}

pub struct Client {
    pub name: String,
    pub uuid: u128,
    state: Arc<Mutex<State>>,
    actions: mpsc::UnboundedSender<Action>,
    task: tokio::task::JoinHandle<()>,
}

impl Client {
    /// Logs in and enters the world. Fails if the server turns it away.
    pub fn connect(addr: SocketAddr, name: &str) -> anyhow::Result<Client> {
        let conn = match runtime().block_on(log_in(addr, name))? {
            Ok(conn) => conn,
            Err(reason) => bail!("{name} was turned away: {reason}"),
        };
        let state = Arc::new(Mutex::new(State::default()));
        let (actions, receiver) = mpsc::unbounded_channel();
        let task = runtime().spawn(play(conn, name.to_owned(), state.clone(), receiver));
        Ok(Client {
            name: name.to_owned(),
            uuid: lodeproxy::auth::offline_uuid(name),
            state,
            actions,
            task,
        })
    }

    /// Tries to log in, and returns why the server turned it away. Fails if it
    /// was let in.
    pub fn refusal(addr: SocketAddr, name: &str) -> anyhow::Result<String> {
        match runtime().block_on(log_in(addr, name))? {
            Ok(_) => bail!("{name} was let in"),
            Err(reason) => Ok(reason),
        }
    }

    pub fn state(&self) -> MutexGuard<'_, State> {
        lock(&self.state)
    }

    /// Polls what the client has seen until `check` is satisfied. Panics if
    /// the connection ends first.
    pub fn wait_for<T>(
        &self,
        what: &str,
        timeout: Duration,
        mut check: impl FnMut(&State) -> Result<T, String>,
    ) -> T {
        crate::wait_for(what, timeout, || {
            let state = self.state();
            if let Some(reason) = &state.ended {
                panic!("{}'s connection ended while waiting for {what}: {reason}", self.name);
            }
            check(&state)
        })
    }

    /// Waits until the server has put it somewhere and sent it chunks.
    pub fn wait_in_world(&self, timeout: Duration) {
        self.wait_for(&format!("{} to be in the world", self.name), timeout, |s| {
            if s.loaded && s.position.is_some() {
                Ok(())
            } else {
                Err(format!("loaded: {}, position: {:?}", s.loaded, s.position))
            }
        });
    }

    pub fn connected(&self) -> bool {
        self.state().ended.is_none()
    }

    /// Walks back and forth along x around where it is, or stops.
    pub fn walk(&self, on: bool) {
        let _ = self.actions.send(Action::Walk(on));
    }

    /// Sends an unsigned chat message, as a client that has no chat key does.
    pub fn chat(&self, text: &str) {
        let _ = self.actions.send(Action::Chat(text.to_owned()));
    }

    pub fn attack(&self, entity: i32) {
        let _ = self.actions.send(Action::Attack(entity));
    }

    /// Uses what it holds on the top of a block, which places a block it holds
    /// on top of that one.
    pub fn use_item_on(&self, x: i32, y: i32, z: i32) {
        let _ = self.actions.send(Action::UseItemOn(x, y, z));
    }

    /// Starts breaking a block, which breaks it at once in creative mode.
    pub fn start_destroy(&self, x: i32, y: i32, z: i32) {
        let _ = self.actions.send(Action::StartDestroy(x, y, z));
    }

    /// Hangs up, as a player closing the game does.
    pub fn quit(self) {
        drop(self);
    }
}

impl Drop for Client {
    fn drop(&mut self) {
        let _ = self.actions.send(Action::Quit);
        self.task.abort();
    }
}

/// Asks for what the server list shows, as a client's server list does, and
/// checks that the server answers a ping with the same number.
pub fn status(addr: SocketAddr) -> anyhow::Result<serde_json::Value> {
    runtime().block_on(async {
        let stream = TcpStream::connect(addr).await?;
        let mut conn = Conn::new(stream, mc::MAX_FRAME);
        let mut out = Vec::new();
        put_varint(&mut out, wire::PROTOCOL);
        put_string(&mut out, &addr.ip().to_string());
        out.extend_from_slice(&addr.port().to_be_bytes());
        put_varint(&mut out, 1); // for its status
        conn.write_packet(0x00, &out).await?;
        conn.write_packet(0x00, &[]).await?;
        let (id, mut packet) = conn.read_packet().await?;
        anyhow::ensure!(id == 0x00, "expected a status, got packet {id:#04x}");
        let status = serde_json::from_str(&get_string(&mut packet, 32767)?)?;
        conn.write_packet(0x01, &42i64.to_be_bytes()).await?;
        let (id, packet) = conn.read_packet().await?;
        anyhow::ensure!(
            id == 0x01 && packet[..] == 42i64.to_be_bytes(),
            "the ping was not answered with its number"
        );
        Ok(status)
    })
}

/// Logs in and goes through configuration. Returns the connection, ready for
/// play, or why the server turned the client away.
async fn log_in(addr: SocketAddr, name: &str) -> anyhow::Result<Result<Conn, String>> {
    let stream = TcpStream::connect(addr)
        .await
        .with_context(|| format!("connecting to {addr}"))?;
    stream.set_nodelay(true)?;
    let mut conn = Conn::new(stream, mc::MAX_FRAME);

    let mut out = Vec::new();
    put_varint(&mut out, wire::PROTOCOL);
    put_string(&mut out, &addr.ip().to_string());
    out.extend_from_slice(&addr.port().to_be_bytes());
    put_varint(&mut out, 2); // to log in
    conn.write_packet(0x00, &out).await?;
    let mut out = Vec::new();
    put_string(&mut out, name);
    out.extend_from_slice(&lodeproxy::auth::offline_uuid(name).to_be_bytes());
    conn.write_packet(login::S_HELLO, &out).await?;

    loop {
        let (id, mut packet) = conn.read_packet().await.context("during login")?;
        match id {
            login::C_DISCONNECT => return Ok(Err(get_string(&mut packet, 1 << 18)?)),
            login::C_ENCRYPTION_REQUEST => bail!("the server wants Mojang authentication"),
            login::C_COMPRESSION => conn.set_compression(get_varint(&mut packet)?),
            // A query a vanilla client does not understand, such as a node
            // asking for the identity a proxy vouches for.
            login::C_CUSTOM_QUERY => {
                let mut out = Vec::new();
                put_varint(&mut out, get_varint(&mut packet)?);
                out.push(0);
                conn.write_packet(login::S_CUSTOM_QUERY_ANSWER, &out).await?;
            }
            login::C_COOKIE_REQUEST => {
                let mut out = Vec::new();
                put_string(&mut out, &get_string(&mut packet, 32767)?);
                out.push(0);
                conn.write_packet(login::S_COOKIE_RESPONSE, &out).await?;
            }
            login::C_LOGIN_FINISHED => {
                conn.write_packet(login::S_LOGIN_ACKNOWLEDGED, &[]).await?;
                break;
            }
            other => bail!("unexpected login packet {other:#04x}"),
        }
    }

    let mut out = Vec::new();
    put_string(&mut out, "en_us");
    out.push(8); // view distance
    put_varint(&mut out, 0); // shows all chat
    out.push(1); // chat colours
    out.push(0x7f); // every skin layer
    put_varint(&mut out, 1); // right-handed
    out.push(0); // no text filtering
    out.push(1); // listed
    put_varint(&mut out, 0); // all particles
    conn.write_packet(config::S_CLIENT_INFORMATION, &out).await?;
    loop {
        let (id, packet) = conn.read_packet().await.context("during configuration")?;
        match id {
            config::C_SELECT_KNOWN_PACKS => {
                conn.write_packet(config::S_SELECT_KNOWN_PACKS, &[0]).await?;
            }
            config::C_KEEP_ALIVE => conn.write_packet(config::S_KEEP_ALIVE, &packet).await?,
            config::C_PING => conn.write_packet(config::S_PONG, &packet).await?,
            config::C_DISCONNECT => return Ok(Err(text(&packet))),
            config::C_FINISH => {
                conn.write_packet(config::S_FINISH, &[]).await?;
                return Ok(Ok(conn));
            }
            _ => {}
        }
    }
}

/// The readable parts of a packet: the strings in a text component's NBT show
/// through well enough to look for words in.
fn text(packet: &[u8]) -> String {
    String::from_utf8_lossy(packet)
        .chars()
        .filter(|c| !c.is_control() || *c == '\n')
        .collect()
}

async fn play(
    mut conn: Conn,
    name: String,
    state: Arc<Mutex<State>>,
    mut actions: mpsc::UnboundedReceiver<Action>,
) {
    let result = Player {
        name,
        state: state.clone(),
        walking: false,
        anchor: [0.0; 3],
        direction: 1.0,
        placed_at: Instant::now(),
        need_loaded: false,
        respawning: false,
        sequence: 0,
    }
    .run(&mut conn, &mut actions)
    .await;
    let reason = match result {
        Ok(()) => "the client hung up".to_owned(),
        Err(e) => format!("{e:#}"),
    };
    lock(&state).ended = Some(reason);
}

struct Player {
    name: String,
    state: Arc<Mutex<State>>,
    walking: bool,
    anchor: [f64; 3],
    direction: f64,
    placed_at: Instant,
    /// Whether it must say it is in the world again, after a respawn.
    need_loaded: bool,
    respawning: bool,
    sequence: i32,
}

fn lock(state: &Mutex<State>) -> MutexGuard<'_, State> {
    state.lock().unwrap_or_else(|e| e.into_inner())
}

impl Player {
    async fn run(
        &mut self,
        conn: &mut Conn,
        actions: &mut mpsc::UnboundedReceiver<Action>,
    ) -> anyhow::Result<()> {
        let mut tick = tokio::time::interval(Duration::from_millis(50));
        tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            tokio::select! {
                _ = tick.tick() => self.on_tick(conn).await?,
                action = actions.recv() => match action {
                    None | Some(Action::Quit) => return Ok(()),
                    Some(action) => self.act(conn, action).await?,
                },
                packet = conn.read_packet() => {
                    let (id, packet) = packet.context("during play")?;
                    self.on_packet(conn, id, packet).await?;
                }
            }
        }
    }

    async fn on_tick(&mut self, conn: &mut Conn) -> anyhow::Result<()> {
        let step = {
            let mut state = lock(&self.state);
            let moving = self.walking && state.loaded && self.placed_at.elapsed() >= SETTLE;
            match &mut state.position {
                Some(pos) if moving => {
                    pos[0] += STEP * self.direction;
                    if (pos[0] - self.anchor[0]).abs() >= REACH {
                        self.direction = -self.direction;
                    }
                    Some(*pos)
                }
                _ => None,
            }
        };
        if let Some(pos) = step {
            let mut out = Vec::new();
            for v in pos {
                out.extend_from_slice(&v.to_be_bytes());
            }
            out.push(1); // on the ground
            conn.write_packet(play::S_MOVE_PLAYER_POS, &out).await?;
        }
        conn.write_packet(play::S_CLIENT_TICK_END, &[]).await?;
        Ok(())
    }

    async fn act(&mut self, conn: &mut Conn, action: Action) -> anyhow::Result<()> {
        match action {
            Action::Walk(on) => {
                self.walking = on;
                let position = lock(&self.state).position;
                if let Some(pos) = position {
                    self.anchor = pos;
                }
            }
            Action::Chat(text) => {
                let mut out = Vec::new();
                put_string(&mut out, &text);
                let now = SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis() as i64;
                out.extend_from_slice(&now.to_be_bytes());
                out.extend_from_slice(&0i64.to_be_bytes()); // salt
                out.push(0); // no signature
                put_varint(&mut out, 0); // last seen: no offset, nothing acknowledged
                out.extend_from_slice(&[0, 0, 0]);
                out.push(0); // no checksum
                conn.write_packet(play::S_CHAT, &out).await?;
                lock(&self.state).chats_sent.push((Instant::now(), text));
            }
            Action::Attack(entity) => {
                let mut out = Vec::new();
                put_varint(&mut out, entity);
                conn.write_packet(play::S_ATTACK, &out).await?;
            }
            Action::UseItemOn(x, y, z) => {
                self.sequence += 1;
                let mut out = Vec::new();
                put_varint(&mut out, 0); // main hand
                out.extend_from_slice(&wire::pack_block_pos(x, y, z).to_be_bytes());
                put_varint(&mut out, 1); // the top face
                for v in [0.5f32, 1.0, 0.5] {
                    out.extend_from_slice(&v.to_be_bytes());
                }
                out.push(0); // not inside the block
                out.push(0); // not the world border
                put_varint(&mut out, self.sequence);
                conn.write_packet(play::S_USE_ITEM_ON, &out).await?;
            }
            Action::StartDestroy(x, y, z) => {
                self.sequence += 1;
                let mut out = Vec::new();
                put_varint(&mut out, 0); // start breaking
                out.extend_from_slice(&wire::pack_block_pos(x, y, z).to_be_bytes());
                out.push(1); // the top face
                put_varint(&mut out, self.sequence);
                conn.write_packet(play::S_PLAYER_ACTION, &out).await?;
            }
            Action::Quit => unreachable!("handled by the caller"),
        }
        Ok(())
    }

    async fn on_packet(&mut self, conn: &mut Conn, id: i32, mut p: Bytes) -> anyhow::Result<()> {
        match id {
            play::C_LOGIN => lock(&self.state).entity_id = Some(wire::get_i32(&mut p)?),
            play::C_KEEP_ALIVE => conn.write_packet(play::S_KEEP_ALIVE, &p).await?,
            play::C_PING => conn.write_packet(play::S_PONG, &p).await?,
            play::C_DISCONNECT => bail!("{} was disconnected: {}", self.name, text(&p)),
            play::C_START_CONFIGURATION => bail!("the server started configuring {} again", self.name),
            play::C_PLAYER_POSITION => {
                let teleport = get_varint(&mut p)?;
                let mut pos = [wire::get_f64(&mut p)?, wire::get_f64(&mut p)?, wire::get_f64(&mut p)?];
                for _ in 0..3 {
                    wire::get_f64(&mut p)?; // velocity
                }
                let yaw = wire::get_f32(&mut p)?;
                let pitch = wire::get_f32(&mut p)?;
                let relative = wire::get_i32(&mut p)?;
                let walking = self.walking && self.placed_at.elapsed() >= SETTLE;
                {
                    let mut state = lock(&self.state);
                    if let Some(old) = state.position {
                        for axis in 0..3 {
                            if relative & (1 << axis) != 0 {
                                pos[axis] += old[axis];
                            }
                        }
                    }
                    state.position = Some(pos);
                    state.events.push((Instant::now(), Event::Teleport { pos, walking }));
                }
                self.anchor = pos;
                self.placed_at = Instant::now();
                let mut out = Vec::new();
                put_varint(&mut out, teleport);
                for v in pos {
                    out.extend_from_slice(&v.to_be_bytes());
                }
                out.extend_from_slice(&yaw.to_be_bytes());
                out.extend_from_slice(&pitch.to_be_bytes());
                conn.write_packet(play::S_ACCEPT_TELEPORTATION, &out).await?;
                if self.need_loaded {
                    self.need_loaded = false;
                    conn.write_packet(play::S_PLAYER_LOADED, &[]).await?;
                }
            }
            play::C_CHUNK_BATCH_FINISHED => {
                conn.write_packet(play::S_CHUNK_BATCH_RECEIVED, &64f32.to_be_bytes()).await?;
                let first = !std::mem::replace(&mut lock(&self.state).loaded, true);
                if first {
                    conn.write_packet(play::S_PLAYER_LOADED, &[]).await?;
                }
            }
            play::C_LEVEL_CHUNK_WITH_LIGHT => {
                let chunk = (wire::get_i32(&mut p)?, wire::get_i32(&mut p)?);
                let mut state = lock(&self.state);
                if !state.chunks.insert(chunk) {
                    state.anomalies.chunks_twice += 1;
                    if state.anomalies.twice_chunks.len() < 20 {
                        state.anomalies.twice_chunks.push(chunk);
                    }
                }
            }
            play::C_FORGET_LEVEL_CHUNK => {
                let packed = wire::get_i64(&mut p)?;
                let chunk = (packed as i32, (packed >> 32) as i32);
                let mut state = lock(&self.state);
                if !state.chunks.remove(&chunk) {
                    state.anomalies.forgot_unknown += 1;
                }
            }
            play::C_ADD_ENTITY => {
                let entity = get_varint(&mut p)?;
                let uuid = wire::get_u128(&mut p)?;
                let kind = get_varint(&mut p)?;
                let mut state = lock(&self.state);
                if state.entities.insert(entity, Entity { uuid, kind }).is_some() {
                    state.anomalies.added_twice += 1;
                }
            }
            play::C_REMOVE_ENTITIES => {
                let count = get_varint(&mut p)?;
                let mut state = lock(&self.state);
                for _ in 0..count {
                    let entity = get_varint(&mut p)?;
                    if state.entities.remove(&entity).is_none() {
                        state.anomalies.removed_unknown += 1;
                    }
                }
            }
            play::C_MOVE_ENTITY_POS
            | play::C_MOVE_ENTITY_POS_ROT
            | play::C_MOVE_ENTITY_ROT
            | play::C_ENTITY_POSITION_SYNC
            | play::C_ROTATE_HEAD
            | play::C_SET_ENTITY_DATA
            | play::C_SET_ENTITY_MOTION => {
                let entity = get_varint(&mut p)?;
                lock(&self.state).touch(entity);
            }
            play::C_PLAYER_CHAT | play::C_DISGUISED_CHAT | play::C_SYSTEM_CHAT => {
                lock(&self.state).events.push((Instant::now(), Event::Chat(text(&p))));
            }
            play::C_SET_HEALTH => {
                let health = wire::get_f32(&mut p)?;
                lock(&self.state).health = Some(health);
                if health <= 0.0 && !self.respawning {
                    self.respawning = true;
                    lock(&self.state).events.push((Instant::now(), Event::Died));
                    conn.write_packet(play::S_CLIENT_COMMAND, &[0]).await?;
                }
            }
            play::C_RESPAWN => {
                self.need_loaded = true;
                self.respawning = false;
                let mut state = lock(&self.state);
                // A client starts the level it is put in afresh.
                state.entities.clear();
                state.chunks.clear();
                state.events.push((Instant::now(), Event::Respawned));
            }
            play::C_BLOCK_UPDATE => {
                let pos = wire::unpack_block_pos(wire::get_i64(&mut p)?);
                let block = get_varint(&mut p)?;
                lock(&self.state).blocks.insert(pos, block);
            }
            play::C_SECTION_BLOCKS_UPDATE => {
                let section = wire::get_i64(&mut p)?;
                let (sx, sy, sz) = (
                    (section >> 42) as i32,
                    (section << 44 >> 44) as i32,
                    (section << 22 >> 42) as i32,
                );
                let count = get_varint(&mut p)?;
                let mut state = lock(&self.state);
                for _ in 0..count {
                    let change = wire::get_varlong(&mut p)?;
                    let local = (change & 0xFFF) as i32;
                    let block = (change as u64 >> 12) as i32;
                    let pos = (
                        sx * 16 + ((local >> 8) & 15),
                        sy * 16 + (local & 15),
                        sz * 16 + ((local >> 4) & 15),
                    );
                    state.blocks.insert(pos, block);
                }
            }
            _ => {}
        }
        Ok(())
    }
}
