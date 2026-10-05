//! Wire protocol spoken between lodestar and its clients (nodes and proxies).
//!
//! The encoding is deliberately plain so that it can be mirrored by hand in the
//! Fabric mod (`mod/src/main/java/dev/lodecore/net/Wire.java`): every frame is a
//! big-endian `u32` length followed by a body, and the body is a one byte
//! message id followed by fixed-width big-endian fields. Strings are a `u16`
//! length plus UTF-8, byte blobs are a `u32` length plus data, and UUIDs are
//! 16 bytes (most significant half first, matching `java.util.UUID`).
//!
//! Any change here must bump [`PROTOCOL_VERSION`] and be mirrored in `Wire.java`.

use bytes::{Buf, BufMut, Bytes, BytesMut};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const PROTOCOL_VERSION: u32 = 9;

/// Entity ids are handed out to nodes in blocks of `1 << ID_BLOCK_SHIFT`, so
/// that an entity can have the same id on every node. Block `b` is the ids
/// from `b << ID_BLOCK_SHIFT` up to the next block.
pub const ID_BLOCK_SHIFT: u32 = 16;

/// Ids the nodes of a cluster must not hand out twice besides entity ids, such
/// as map ids, are handed out in blocks of `1 << COUNTER_BLOCK_SHIFT`. Block
/// `b` of a counter is its ids from `b << COUNTER_BLOCK_SHIFT` up to the next
/// block. See [`Message::CounterBlockRequest`].
pub const COUNTER_BLOCK_SHIFT: u32 = 12;
/// The counter of map ids: the number of each filled map.
pub const COUNTER_MAP_IDS: u8 = 0;
/// The counter of raid ids, by which raiders know their raid.
pub const COUNTER_RAID_IDS: u8 = 1;
/// How many counters there are.
pub const COUNTERS: u8 = 2;

/// Upper bound on a frame body. Anything larger is treated as a protocol error.
pub const MAX_FRAME: usize = 4 * 1024 * 1024;

/// Identifies a node for the lifetime of its connection. `NO_NODE` means "nobody".
pub type NodeId = u32;
pub const NO_NODE: NodeId = 0;

/// The scope of the state a server keeps once for all of its dimensions: the
/// time of day, the weather, the game rules and the like. Every other scope is
/// a dimension, by the id [`Message::DimensionResolved`] handed out.
pub const CLUSTER_SCOPE: u32 = u32::MAX;

/// A region is a square of `1 << REGION_SHIFT` chunks on a side. It is the
/// unit in which the simulation of a dimension is handed out to nodes.
pub const REGION_SHIFT: u32 = 3;

/// A chunk column. `dim` is an id handed out by [`Message::DimensionResolved`].
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub struct ChunkKey {
    pub dim: u32,
    pub x: i32,
    pub z: i32,
}

/// A region of a dimension, in region coordinates: chunk `x >> REGION_SHIFT`.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Debug)]
pub struct RegionKey {
    pub dim: u32,
    pub x: i32,
    pub z: i32,
}

impl ChunkKey {
    pub fn region(&self) -> RegionKey {
        RegionKey {
            dim: self.dim,
            x: self.x >> REGION_SHIFT,
            z: self.z >> REGION_SHIFT,
        }
    }
}

#[derive(Clone, PartialEq, Eq, Debug)]
pub enum Role {
    /// A Minecraft server running the Lodecore mod.
    Node {
        /// Address the proxy should dial to reach this node's game port.
        game_addr: String,
        max_players: u32,
    },
    Proxy,
}

/// How much a region's owner is asked to do with a chunk on behalf of the
/// other nodes.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Debug)]
pub enum Demand {
    /// No other node has the chunk loaded.
    None = 0,
    /// Some node has the chunk loaded, so the owner must hold it too: its copy
    /// is the one the others take theirs from.
    Loaded = 1,
    /// Some node would tick the chunk, so the owner must tick it.
    Ticking = 2,
}

#[derive(Clone, PartialEq, Eq, Debug)]
pub enum PlacementOutcome {
    Node {
        node: NodeId,
        addr: String,
    },
    /// `reason` is shown to the player.
    Denied {
        reason: String,
    },
}

#[derive(Clone, PartialEq, Eq, Debug)]
pub enum Message {
    // ---- client -> lodestar ----
    /// Must be the first message on a connection.
    Hello {
        protocol: u32,
        token: String,
        name: String,
        role: Role,
    },
    Heartbeat {
        mspt_micros: u32,
        players: u32,
    },
    /// Asks for a dimension's id, and makes the node one of the dimension's
    /// workers.
    ResolveDimension {
        name: String,
    },
    PlayerJoin {
        uuid: u128,
        name: String,
    },
    PlayerLeave {
        uuid: u128,
    },
    /// Join a chunk's topic: the node has the chunk loaded.
    Subscribe {
        chunk: ChunkKey,
    },
    Unsubscribe {
        chunk: ChunkKey,
    },
    /// Says whether the node would tick a chunk it subscribes to, were it the
    /// owner of the chunk's region. The owner is then asked to tick it.
    SetTicking {
        chunk: ChunkKey,
        ticking: bool,
    },
    /// The node's copy of a chunk it subscribes to is known to match the
    /// owner's: it took a snapshot from the owner, or it is the owner. Holds
    /// until the node unsubscribes.
    Synced {
        chunk: ChunkKey,
    },
    /// Where a player homed on this node is. Sent when the player joins and
    /// whenever they move into another chunk.
    PlayerAt {
        uuid: u128,
        chunk: ChunkKey,
    },
    /// Asks for custody of an object in `chunk`: an entity or a block entity,
    /// named by `object`, which is opaque to lodestar. While a node has custody
    /// of an object, it is the object's authority instead of the owner of the
    /// object's region. `claim` is the requester's own number for the claim.
    Claim {
        claim: u32,
        chunk: ChunkKey,
        object: Bytes,
    },
    /// The owner's answer to a [`Message::ClaimRequest`]. `payload` is the
    /// object's state, handed to the claimant.
    ClaimAnswer {
        claim: u32,
        claimant: NodeId,
        granted: bool,
        payload: Bytes,
    },
    /// Gives up custody of an object, which is now in `chunk`, with its state.
    Release {
        chunk: ChunkKey,
        object: Bytes,
        payload: Bytes,
    },
    /// Asks for a player's saved data, and custody of it: until the node
    /// releases it, no other node is given the data.
    PlayerDataRequest {
        uuid: u128,
    },
    /// Saves the data of a player this node has custody of.
    PlayerDataSave {
        uuid: u128,
        data: Bytes,
    },
    /// Gives up custody of a player's data, once its last save has been sent.
    PlayerDataRelease {
        uuid: u128,
    },
    /// Relayed to every other subscriber of `chunk`. The payload is opaque to lodestar.
    Publish {
        chunk: ChunkKey,
        payload: Bytes,
    },
    /// Relayed to a single node.
    Direct {
        to: NodeId,
        payload: Bytes,
    },
    /// From the master of a scope: relayed to every other node of the scope,
    /// which for a dimension is its workers. Dropped if the sender is not the
    /// scope's master. The payload is opaque to lodestar.
    Broadcast {
        scope: u32,
        payload: Bytes,
    },
    PlaceRequest {
        request_id: u32,
        uuid: u128,
        name: String,
    },
    StatusRequest {
        request_id: u32,
    },
    /// The node has finished the tick lodestar started with `Tick`.
    TickDone {
        tick: u64,
    },
    /// Asks for a block of entity ids of the node's own, answered with
    /// [`Message::IdBlock`].
    IdBlockRequest,
    /// Asks for a block of one of the counters ([`COUNTER_MAP_IDS`],
    /// [`COUNTER_RAID_IDS`]) for the node alone to hand out, answered with
    /// [`Message::CounterBlock`]. `floor` is the lowest id the node may hand
    /// out: ids below it may be in use already, in its own save.
    CounterBlockRequest {
        counter: u8,
        floor: u32,
    },
    /// Relayed to every other node, from any node: what happens once for the
    /// whole cluster, such as chat. The payload is opaque to lodestar.
    Announce {
        payload: Bytes,
    },
    /// From the master of the cluster's state: how long a tick should take,
    /// as `/tick` has set it. Zero runs ticks as fast as the nodes can.
    TickInterval {
        nanos: u64,
    },
    /// Asks lodestar to move a connected player to another node.
    HandoffRequest {
        uuid: u128,
        to: NodeId,
    },
    /// From the node a player is moving to: it has the world around the
    /// player, and the proxy's new connection for them.
    HandoffReady {
        uuid: u128,
    },
    /// From the node a player is moving from: it has let go of the player.
    /// `data` is the player's saved data, as [`Message::PlayerDataSave`]
    /// carries it, and `state` what their client was last sent, which is
    /// opaque to lodestar.
    HandoffState {
        uuid: u128,
        data: Bytes,
        state: Bytes,
    },
    /// The node gives up its part in moving a player: the node they are
    /// moving from keeps them, or the one they are moving to could not take
    /// them.
    HandoffAbort {
        uuid: u128,
    },
    /// From the node a player moved to: the player is there.
    HandoffDone {
        uuid: u128,
    },
    /// From a proxy: it cannot do its part in moving a player.
    HandoffFailed {
        uuid: u128,
    },
    /// From a proxy: it has stopped passing on what the player's client sends.
    /// `frames` is how many packets it has written to the player's node.
    HandoffFrozen {
        uuid: u128,
        frames: u64,
    },

    // ---- lodestar -> client ----
    /// `node_id` is [`NO_NODE`] for proxies.
    Welcome {
        node_id: NodeId,
    },
    Rejected {
        reason: String,
    },
    DimensionResolved {
        name: String,
        id: u32,
    },
    /// Names the node that simulates a region, or [`NO_NODE`] once nobody has
    /// any of it loaded. Sent to each of the dimension's workers when it joins
    /// and whenever the owner changes, which is only ever between two ticks.
    RegionOwner {
        region: RegionKey,
        owner: NodeId,
    },
    /// Asks the owner of an object's region to hand the object to `claimant`.
    ClaimRequest {
        claim: u32,
        claimant: NodeId,
        chunk: ChunkKey,
        object: Bytes,
    },
    /// The outcome of a [`Message::Claim`]. Sent after the [`Message::Custody`]
    /// that a granted claim leads to.
    ClaimResult {
        claim: u32,
        granted: bool,
        payload: Bytes,
    },
    /// Which node has custody of an object in a dimension, or [`NO_NODE`]
    /// once its region's owner is its authority again. Sent to every worker of
    /// the dimension.
    Custody {
        dim: u32,
        object: Bytes,
        holder: NodeId,
    },
    /// An object handed back to the owner of the region it is in, with its
    /// state. The payload is empty if the holder left without handing it back.
    Released {
        chunk: ChunkKey,
        object: Bytes,
        payload: Bytes,
    },
    /// A player's saved data, or `None` if lodestar has none yet.
    PlayerData {
        uuid: u128,
        data: Option<Bytes>,
    },
    /// Tells a node what the other nodes need of a chunk: the owner of the
    /// chunk's region, or the node the region is being handed to.
    ChunkDemand {
        chunk: ChunkKey,
        demand: Demand,
    },
    /// Starts a tick on every node. The next one is only started once every
    /// node has answered with `TickDone`.
    Tick {
        tick: u64,
    },
    Relay {
        from: NodeId,
        chunk: ChunkKey,
        payload: Bytes,
    },
    DirectRelay {
        from: NodeId,
        payload: Bytes,
    },
    /// Names the node that keeps the state of a scope ([`CLUSTER_SCOPE`] or a
    /// dimension), which every other node of the scope adopts. Sent to each
    /// node of the scope when it joins and whenever the master changes, which
    /// is only ever between two ticks.
    Master {
        scope: u32,
        master: NodeId,
    },
    BroadcastRelay {
        from: NodeId,
        scope: u32,
        payload: Bytes,
    },
    Placement {
        request_id: u32,
        outcome: PlacementOutcome,
    },
    Status {
        request_id: u32,
        online: u32,
        capacity: u32,
    },
    NodeUp {
        node: NodeId,
        name: String,
    },
    NodeDown {
        node: NodeId,
    },
    PlayerJoined {
        node: NodeId,
        uuid: u128,
        name: String,
    },
    PlayerLeft {
        node: NodeId,
        uuid: u128,
    },
    /// A block of entity ids, for the node alone to hand out.
    IdBlock {
        block: u32,
    },
    /// What another node announced to the whole cluster.
    AnnounceRelay {
        from: NodeId,
        payload: Bytes,
    },
    /// A block of a counter's ids, for the node alone to hand out.
    CounterBlock {
        counter: u8,
        block: u32,
    },
    /// To the node a player is to move to: get ready to take them. `chunk` is
    /// where they were last reported. The proxy will log in with `token`.
    HandoffPrepare {
        uuid: u128,
        token: u64,
        chunk: ChunkKey,
    },
    /// To the node a player is moving from: let go of them once you have
    /// handled the first `frames` packets of their connection, which are all
    /// the proxy has passed on.
    HandoffCut {
        uuid: u128,
        to: NodeId,
        dim: u32,
        frames: u64,
    },
    /// To the node a player is moving to: take them over.
    HandoffArrive {
        uuid: u128,
        data: Bytes,
        state: Bytes,
    },
    /// Stops moving a player. To a node, or to the proxy.
    HandoffCancel {
        uuid: u128,
    },
    /// To a proxy: log the player in to the node at `addr` too, presenting
    /// `token`, and hold on to that connection.
    HandoffDial {
        uuid: u128,
        token: u64,
        addr: String,
    },
    /// To a proxy: stop passing on what the player's client sends.
    HandoffFreeze {
        uuid: u128,
    },
    /// To a proxy: the player has moved; once their old node has hung up,
    /// relay them to the new connection.
    HandoffSwitch {
        uuid: u128,
    },
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum DecodeError {
    #[error("message is truncated")]
    Truncated,
    #[error("unknown message id {0:#04x}")]
    UnknownMessage(u8),
    #[error("unknown enum tag {0}")]
    UnknownTag(u8),
    #[error("string is not valid UTF-8")]
    BadUtf8,
    #[error("{0} trailing bytes after message")]
    Trailing(usize),
}

mod id {
    pub const HELLO: u8 = 0x01;
    pub const HEARTBEAT: u8 = 0x02;
    pub const RESOLVE_DIMENSION: u8 = 0x03;
    pub const PLAYER_JOIN: u8 = 0x04;
    pub const PLAYER_LEAVE: u8 = 0x05;
    pub const SUBSCRIBE: u8 = 0x07;
    pub const UNSUBSCRIBE: u8 = 0x08;
    pub const PUBLISH: u8 = 0x09;
    pub const DIRECT: u8 = 0x0A;
    pub const PLACE_REQUEST: u8 = 0x0B;
    pub const STATUS_REQUEST: u8 = 0x0C;
    pub const SET_TICKING: u8 = 0x0D;
    pub const TICK_DONE: u8 = 0x0E;
    pub const SYNCED: u8 = 0x0F;
    pub const PLAYER_AT: u8 = 0x10;
    pub const CLAIM: u8 = 0x11;
    pub const CLAIM_ANSWER: u8 = 0x12;
    pub const RELEASE: u8 = 0x13;
    pub const PLAYER_DATA_REQUEST: u8 = 0x14;
    pub const PLAYER_DATA_SAVE: u8 = 0x15;
    pub const PLAYER_DATA_RELEASE: u8 = 0x16;
    pub const ID_BLOCK_REQUEST: u8 = 0x17;
    pub const HANDOFF_REQUEST: u8 = 0x18;
    pub const HANDOFF_READY: u8 = 0x19;
    pub const HANDOFF_STATE: u8 = 0x1A;
    pub const HANDOFF_ABORT: u8 = 0x1B;
    pub const HANDOFF_DONE: u8 = 0x1C;
    pub const HANDOFF_FAILED: u8 = 0x1D;
    pub const HANDOFF_FROZEN: u8 = 0x1E;
    pub const BROADCAST: u8 = 0x1F;
    pub const COUNTER_BLOCK_REQUEST: u8 = 0x20;
    pub const TICK_INTERVAL: u8 = 0x21;
    pub const ANNOUNCE: u8 = 0x22;

    pub const WELCOME: u8 = 0x81;
    pub const REJECTED: u8 = 0x82;
    pub const DIMENSION_RESOLVED: u8 = 0x83;
    pub const RELAY: u8 = 0x85;
    pub const DIRECT_RELAY: u8 = 0x86;
    pub const PLACEMENT: u8 = 0x87;
    pub const STATUS: u8 = 0x88;
    pub const NODE_UP: u8 = 0x89;
    pub const NODE_DOWN: u8 = 0x8A;
    pub const PLAYER_JOINED: u8 = 0x8B;
    pub const PLAYER_LEFT: u8 = 0x8C;
    pub const CHUNK_DEMAND: u8 = 0x8E;
    pub const TICK: u8 = 0x8F;
    pub const REGION_OWNER: u8 = 0x90;
    pub const CLAIM_REQUEST: u8 = 0x91;
    pub const CLAIM_RESULT: u8 = 0x92;
    pub const CUSTODY: u8 = 0x93;
    pub const RELEASED: u8 = 0x94;
    pub const PLAYER_DATA: u8 = 0x95;
    pub const ID_BLOCK: u8 = 0x96;
    pub const HANDOFF_PREPARE: u8 = 0x97;
    pub const HANDOFF_CUT: u8 = 0x98;
    pub const HANDOFF_ARRIVE: u8 = 0x99;
    pub const HANDOFF_CANCEL: u8 = 0x9A;
    pub const HANDOFF_DIAL: u8 = 0x9B;
    pub const HANDOFF_FREEZE: u8 = 0x9C;
    pub const HANDOFF_SWITCH: u8 = 0x9D;
    pub const MASTER: u8 = 0x9E;
    pub const BROADCAST_RELAY: u8 = 0x9F;
    pub const COUNTER_BLOCK: u8 = 0xA0;
    pub const ANNOUNCE_RELAY: u8 = 0xA1;
}

fn put_str(out: &mut BytesMut, s: &str) {
    // Strings on this protocol are names and short reasons; clamp rather than fail.
    let mut end = s.len().min(u16::MAX as usize);
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    out.put_u16(end as u16);
    out.put_slice(&s.as_bytes()[..end]);
}

fn put_bytes(out: &mut BytesMut, b: &[u8]) {
    out.put_u32(b.len() as u32);
    out.put_slice(b);
}

fn put_chunk(out: &mut BytesMut, c: &ChunkKey) {
    out.put_u32(c.dim);
    out.put_i32(c.x);
    out.put_i32(c.z);
}

fn put_region(out: &mut BytesMut, r: &RegionKey) {
    out.put_u32(r.dim);
    out.put_i32(r.x);
    out.put_i32(r.z);
}

fn need(b: &Bytes, n: usize) -> Result<(), DecodeError> {
    if b.remaining() < n {
        Err(DecodeError::Truncated)
    } else {
        Ok(())
    }
}

fn get_u8(b: &mut Bytes) -> Result<u8, DecodeError> {
    need(b, 1)?;
    Ok(b.get_u8())
}

fn get_u32(b: &mut Bytes) -> Result<u32, DecodeError> {
    need(b, 4)?;
    Ok(b.get_u32())
}

fn get_u64(b: &mut Bytes) -> Result<u64, DecodeError> {
    need(b, 8)?;
    Ok(b.get_u64())
}

fn get_uuid(b: &mut Bytes) -> Result<u128, DecodeError> {
    need(b, 16)?;
    Ok(b.get_u128())
}

fn get_str(b: &mut Bytes) -> Result<String, DecodeError> {
    need(b, 2)?;
    let len = b.get_u16() as usize;
    need(b, len)?;
    String::from_utf8(b.split_to(len).to_vec()).map_err(|_| DecodeError::BadUtf8)
}

fn get_bytes(b: &mut Bytes) -> Result<Bytes, DecodeError> {
    let len = get_u32(b)? as usize;
    need(b, len)?;
    Ok(b.split_to(len))
}

fn get_chunk(b: &mut Bytes) -> Result<ChunkKey, DecodeError> {
    need(b, 12)?;
    Ok(ChunkKey {
        dim: b.get_u32(),
        x: b.get_i32(),
        z: b.get_i32(),
    })
}

fn get_region(b: &mut Bytes) -> Result<RegionKey, DecodeError> {
    need(b, 12)?;
    Ok(RegionKey {
        dim: b.get_u32(),
        x: b.get_i32(),
        z: b.get_i32(),
    })
}

impl Message {
    /// Appends the message body (id and fields, without the frame length).
    pub fn encode(&self, out: &mut BytesMut) {
        match self {
            Message::Hello {
                protocol,
                token,
                name,
                role,
            } => {
                out.put_u8(id::HELLO);
                out.put_u32(*protocol);
                put_str(out, token);
                put_str(out, name);
                match role {
                    Role::Node {
                        game_addr,
                        max_players,
                    } => {
                        out.put_u8(0);
                        put_str(out, game_addr);
                        out.put_u32(*max_players);
                    }
                    Role::Proxy => out.put_u8(1),
                }
            }
            Message::Heartbeat {
                mspt_micros,
                players,
            } => {
                out.put_u8(id::HEARTBEAT);
                out.put_u32(*mspt_micros);
                out.put_u32(*players);
            }
            Message::ResolveDimension { name } => {
                out.put_u8(id::RESOLVE_DIMENSION);
                put_str(out, name);
            }
            Message::PlayerJoin { uuid, name } => {
                out.put_u8(id::PLAYER_JOIN);
                out.put_u128(*uuid);
                put_str(out, name);
            }
            Message::PlayerLeave { uuid } => {
                out.put_u8(id::PLAYER_LEAVE);
                out.put_u128(*uuid);
            }
            Message::Subscribe { chunk } => {
                out.put_u8(id::SUBSCRIBE);
                put_chunk(out, chunk);
            }
            Message::Unsubscribe { chunk } => {
                out.put_u8(id::UNSUBSCRIBE);
                put_chunk(out, chunk);
            }
            Message::SetTicking { chunk, ticking } => {
                out.put_u8(id::SET_TICKING);
                put_chunk(out, chunk);
                out.put_u8(u8::from(*ticking));
            }
            Message::Synced { chunk } => {
                out.put_u8(id::SYNCED);
                put_chunk(out, chunk);
            }
            Message::PlayerAt { uuid, chunk } => {
                out.put_u8(id::PLAYER_AT);
                out.put_u128(*uuid);
                put_chunk(out, chunk);
            }
            Message::Claim {
                claim,
                chunk,
                object,
            } => {
                out.put_u8(id::CLAIM);
                out.put_u32(*claim);
                put_chunk(out, chunk);
                put_bytes(out, object);
            }
            Message::ClaimAnswer {
                claim,
                claimant,
                granted,
                payload,
            } => {
                out.put_u8(id::CLAIM_ANSWER);
                out.put_u32(*claim);
                out.put_u32(*claimant);
                out.put_u8(u8::from(*granted));
                put_bytes(out, payload);
            }
            Message::Release {
                chunk,
                object,
                payload,
            } => {
                out.put_u8(id::RELEASE);
                put_chunk(out, chunk);
                put_bytes(out, object);
                put_bytes(out, payload);
            }
            Message::PlayerDataRequest { uuid } => {
                out.put_u8(id::PLAYER_DATA_REQUEST);
                out.put_u128(*uuid);
            }
            Message::PlayerDataSave { uuid, data } => {
                out.put_u8(id::PLAYER_DATA_SAVE);
                out.put_u128(*uuid);
                put_bytes(out, data);
            }
            Message::PlayerDataRelease { uuid } => {
                out.put_u8(id::PLAYER_DATA_RELEASE);
                out.put_u128(*uuid);
            }
            Message::ClaimRequest {
                claim,
                claimant,
                chunk,
                object,
            } => {
                out.put_u8(id::CLAIM_REQUEST);
                out.put_u32(*claim);
                out.put_u32(*claimant);
                put_chunk(out, chunk);
                put_bytes(out, object);
            }
            Message::ClaimResult {
                claim,
                granted,
                payload,
            } => {
                out.put_u8(id::CLAIM_RESULT);
                out.put_u32(*claim);
                out.put_u8(u8::from(*granted));
                put_bytes(out, payload);
            }
            Message::Custody {
                dim,
                object,
                holder,
            } => {
                out.put_u8(id::CUSTODY);
                out.put_u32(*dim);
                put_bytes(out, object);
                out.put_u32(*holder);
            }
            Message::Released {
                chunk,
                object,
                payload,
            } => {
                out.put_u8(id::RELEASED);
                put_chunk(out, chunk);
                put_bytes(out, object);
                put_bytes(out, payload);
            }
            Message::PlayerData { uuid, data } => {
                out.put_u8(id::PLAYER_DATA);
                out.put_u128(*uuid);
                match data {
                    Some(data) => {
                        out.put_u8(1);
                        put_bytes(out, data);
                    }
                    None => out.put_u8(0),
                }
            }
            Message::Publish { chunk, payload } => {
                out.put_u8(id::PUBLISH);
                put_chunk(out, chunk);
                put_bytes(out, payload);
            }
            Message::Direct { to, payload } => {
                out.put_u8(id::DIRECT);
                out.put_u32(*to);
                put_bytes(out, payload);
            }
            Message::Broadcast { scope, payload } => {
                out.put_u8(id::BROADCAST);
                out.put_u32(*scope);
                put_bytes(out, payload);
            }
            Message::PlaceRequest {
                request_id,
                uuid,
                name,
            } => {
                out.put_u8(id::PLACE_REQUEST);
                out.put_u32(*request_id);
                out.put_u128(*uuid);
                put_str(out, name);
            }
            Message::StatusRequest { request_id } => {
                out.put_u8(id::STATUS_REQUEST);
                out.put_u32(*request_id);
            }
            Message::TickDone { tick } => {
                out.put_u8(id::TICK_DONE);
                out.put_u64(*tick);
            }
            Message::Welcome { node_id } => {
                out.put_u8(id::WELCOME);
                out.put_u32(*node_id);
            }
            Message::Rejected { reason } => {
                out.put_u8(id::REJECTED);
                put_str(out, reason);
            }
            Message::DimensionResolved { name, id } => {
                out.put_u8(id::DIMENSION_RESOLVED);
                put_str(out, name);
                out.put_u32(*id);
            }
            Message::RegionOwner { region, owner } => {
                out.put_u8(id::REGION_OWNER);
                put_region(out, region);
                out.put_u32(*owner);
            }
            Message::ChunkDemand { chunk, demand } => {
                out.put_u8(id::CHUNK_DEMAND);
                put_chunk(out, chunk);
                out.put_u8(*demand as u8);
            }
            Message::Tick { tick } => {
                out.put_u8(id::TICK);
                out.put_u64(*tick);
            }
            Message::Relay {
                from,
                chunk,
                payload,
            } => {
                out.put_u8(id::RELAY);
                out.put_u32(*from);
                put_chunk(out, chunk);
                put_bytes(out, payload);
            }
            Message::DirectRelay { from, payload } => {
                out.put_u8(id::DIRECT_RELAY);
                out.put_u32(*from);
                put_bytes(out, payload);
            }
            Message::Master { scope, master } => {
                out.put_u8(id::MASTER);
                out.put_u32(*scope);
                out.put_u32(*master);
            }
            Message::BroadcastRelay {
                from,
                scope,
                payload,
            } => {
                out.put_u8(id::BROADCAST_RELAY);
                out.put_u32(*from);
                out.put_u32(*scope);
                put_bytes(out, payload);
            }
            Message::Placement {
                request_id,
                outcome,
            } => {
                out.put_u8(id::PLACEMENT);
                out.put_u32(*request_id);
                match outcome {
                    PlacementOutcome::Node { node, addr } => {
                        out.put_u8(0);
                        out.put_u32(*node);
                        put_str(out, addr);
                    }
                    PlacementOutcome::Denied { reason } => {
                        out.put_u8(1);
                        put_str(out, reason);
                    }
                }
            }
            Message::Status {
                request_id,
                online,
                capacity,
            } => {
                out.put_u8(id::STATUS);
                out.put_u32(*request_id);
                out.put_u32(*online);
                out.put_u32(*capacity);
            }
            Message::NodeUp { node, name } => {
                out.put_u8(id::NODE_UP);
                out.put_u32(*node);
                put_str(out, name);
            }
            Message::NodeDown { node } => {
                out.put_u8(id::NODE_DOWN);
                out.put_u32(*node);
            }
            Message::PlayerJoined { node, uuid, name } => {
                out.put_u8(id::PLAYER_JOINED);
                out.put_u32(*node);
                out.put_u128(*uuid);
                put_str(out, name);
            }
            Message::PlayerLeft { node, uuid } => {
                out.put_u8(id::PLAYER_LEFT);
                out.put_u32(*node);
                out.put_u128(*uuid);
            }
            Message::IdBlockRequest => out.put_u8(id::ID_BLOCK_REQUEST),
            Message::CounterBlockRequest { counter, floor } => {
                out.put_u8(id::COUNTER_BLOCK_REQUEST);
                out.put_u8(*counter);
                out.put_u32(*floor);
            }
            Message::TickInterval { nanos } => {
                out.put_u8(id::TICK_INTERVAL);
                out.put_u64(*nanos);
            }
            Message::Announce { payload } => {
                out.put_u8(id::ANNOUNCE);
                put_bytes(out, payload);
            }
            Message::HandoffRequest { uuid, to } => {
                out.put_u8(id::HANDOFF_REQUEST);
                out.put_u128(*uuid);
                out.put_u32(*to);
            }
            Message::HandoffReady { uuid } => {
                out.put_u8(id::HANDOFF_READY);
                out.put_u128(*uuid);
            }
            Message::HandoffState { uuid, data, state } => {
                out.put_u8(id::HANDOFF_STATE);
                out.put_u128(*uuid);
                put_bytes(out, data);
                put_bytes(out, state);
            }
            Message::HandoffAbort { uuid } => {
                out.put_u8(id::HANDOFF_ABORT);
                out.put_u128(*uuid);
            }
            Message::HandoffDone { uuid } => {
                out.put_u8(id::HANDOFF_DONE);
                out.put_u128(*uuid);
            }
            Message::HandoffFailed { uuid } => {
                out.put_u8(id::HANDOFF_FAILED);
                out.put_u128(*uuid);
            }
            Message::HandoffFrozen { uuid, frames } => {
                out.put_u8(id::HANDOFF_FROZEN);
                out.put_u128(*uuid);
                out.put_u64(*frames);
            }
            Message::IdBlock { block } => {
                out.put_u8(id::ID_BLOCK);
                out.put_u32(*block);
            }
            Message::CounterBlock { counter, block } => {
                out.put_u8(id::COUNTER_BLOCK);
                out.put_u8(*counter);
                out.put_u32(*block);
            }
            Message::AnnounceRelay { from, payload } => {
                out.put_u8(id::ANNOUNCE_RELAY);
                out.put_u32(*from);
                put_bytes(out, payload);
            }
            Message::HandoffPrepare { uuid, token, chunk } => {
                out.put_u8(id::HANDOFF_PREPARE);
                out.put_u128(*uuid);
                out.put_u64(*token);
                put_chunk(out, chunk);
            }
            Message::HandoffCut {
                uuid,
                to,
                dim,
                frames,
            } => {
                out.put_u8(id::HANDOFF_CUT);
                out.put_u128(*uuid);
                out.put_u32(*to);
                out.put_u32(*dim);
                out.put_u64(*frames);
            }
            Message::HandoffArrive { uuid, data, state } => {
                out.put_u8(id::HANDOFF_ARRIVE);
                out.put_u128(*uuid);
                put_bytes(out, data);
                put_bytes(out, state);
            }
            Message::HandoffCancel { uuid } => {
                out.put_u8(id::HANDOFF_CANCEL);
                out.put_u128(*uuid);
            }
            Message::HandoffDial { uuid, token, addr } => {
                out.put_u8(id::HANDOFF_DIAL);
                out.put_u128(*uuid);
                out.put_u64(*token);
                put_str(out, addr);
            }
            Message::HandoffFreeze { uuid } => {
                out.put_u8(id::HANDOFF_FREEZE);
                out.put_u128(*uuid);
            }
            Message::HandoffSwitch { uuid } => {
                out.put_u8(id::HANDOFF_SWITCH);
                out.put_u128(*uuid);
            }
        }
    }

    /// Encodes the message as a complete frame, ready to be written to a socket.
    pub fn to_frame(&self) -> Bytes {
        let mut out = BytesMut::with_capacity(64);
        out.put_u32(0);
        self.encode(&mut out);
        let len = (out.len() - 4) as u32;
        out[..4].copy_from_slice(&len.to_be_bytes());
        out.freeze()
    }

    /// Decodes a frame body. Payloads borrow from `body` without copying.
    pub fn decode(mut body: Bytes) -> Result<Message, DecodeError> {
        let b = &mut body;
        let msg = match get_u8(b)? {
            id::HELLO => {
                let protocol = get_u32(b)?;
                let token = get_str(b)?;
                let name = get_str(b)?;
                let role = match get_u8(b)? {
                    0 => Role::Node {
                        game_addr: get_str(b)?,
                        max_players: get_u32(b)?,
                    },
                    1 => Role::Proxy,
                    tag => return Err(DecodeError::UnknownTag(tag)),
                };
                Message::Hello {
                    protocol,
                    token,
                    name,
                    role,
                }
            }
            id::HEARTBEAT => Message::Heartbeat {
                mspt_micros: get_u32(b)?,
                players: get_u32(b)?,
            },
            id::RESOLVE_DIMENSION => Message::ResolveDimension { name: get_str(b)? },
            id::PLAYER_JOIN => Message::PlayerJoin {
                uuid: get_uuid(b)?,
                name: get_str(b)?,
            },
            id::PLAYER_LEAVE => Message::PlayerLeave { uuid: get_uuid(b)? },
            id::SUBSCRIBE => Message::Subscribe {
                chunk: get_chunk(b)?,
            },
            id::UNSUBSCRIBE => Message::Unsubscribe {
                chunk: get_chunk(b)?,
            },
            id::SET_TICKING => Message::SetTicking {
                chunk: get_chunk(b)?,
                ticking: get_u8(b)? != 0,
            },
            id::SYNCED => Message::Synced {
                chunk: get_chunk(b)?,
            },
            id::PLAYER_AT => Message::PlayerAt {
                uuid: get_uuid(b)?,
                chunk: get_chunk(b)?,
            },
            id::CLAIM => Message::Claim {
                claim: get_u32(b)?,
                chunk: get_chunk(b)?,
                object: get_bytes(b)?,
            },
            id::CLAIM_ANSWER => Message::ClaimAnswer {
                claim: get_u32(b)?,
                claimant: get_u32(b)?,
                granted: get_u8(b)? != 0,
                payload: get_bytes(b)?,
            },
            id::RELEASE => Message::Release {
                chunk: get_chunk(b)?,
                object: get_bytes(b)?,
                payload: get_bytes(b)?,
            },
            id::PLAYER_DATA_REQUEST => Message::PlayerDataRequest { uuid: get_uuid(b)? },
            id::PLAYER_DATA_SAVE => Message::PlayerDataSave {
                uuid: get_uuid(b)?,
                data: get_bytes(b)?,
            },
            id::PLAYER_DATA_RELEASE => Message::PlayerDataRelease { uuid: get_uuid(b)? },
            id::CLAIM_REQUEST => Message::ClaimRequest {
                claim: get_u32(b)?,
                claimant: get_u32(b)?,
                chunk: get_chunk(b)?,
                object: get_bytes(b)?,
            },
            id::CLAIM_RESULT => Message::ClaimResult {
                claim: get_u32(b)?,
                granted: get_u8(b)? != 0,
                payload: get_bytes(b)?,
            },
            id::CUSTODY => Message::Custody {
                dim: get_u32(b)?,
                object: get_bytes(b)?,
                holder: get_u32(b)?,
            },
            id::RELEASED => Message::Released {
                chunk: get_chunk(b)?,
                object: get_bytes(b)?,
                payload: get_bytes(b)?,
            },
            id::PLAYER_DATA => Message::PlayerData {
                uuid: get_uuid(b)?,
                data: match get_u8(b)? {
                    0 => None,
                    1 => Some(get_bytes(b)?),
                    tag => return Err(DecodeError::UnknownTag(tag)),
                },
            },
            id::PUBLISH => Message::Publish {
                chunk: get_chunk(b)?,
                payload: get_bytes(b)?,
            },
            id::DIRECT => Message::Direct {
                to: get_u32(b)?,
                payload: get_bytes(b)?,
            },
            id::BROADCAST => Message::Broadcast {
                scope: get_u32(b)?,
                payload: get_bytes(b)?,
            },
            id::PLACE_REQUEST => Message::PlaceRequest {
                request_id: get_u32(b)?,
                uuid: get_uuid(b)?,
                name: get_str(b)?,
            },
            id::STATUS_REQUEST => Message::StatusRequest {
                request_id: get_u32(b)?,
            },
            id::TICK_DONE => Message::TickDone { tick: get_u64(b)? },
            id::WELCOME => Message::Welcome {
                node_id: get_u32(b)?,
            },
            id::REJECTED => Message::Rejected {
                reason: get_str(b)?,
            },
            id::DIMENSION_RESOLVED => Message::DimensionResolved {
                name: get_str(b)?,
                id: get_u32(b)?,
            },
            id::REGION_OWNER => Message::RegionOwner {
                region: get_region(b)?,
                owner: get_u32(b)?,
            },
            id::CHUNK_DEMAND => Message::ChunkDemand {
                chunk: get_chunk(b)?,
                demand: match get_u8(b)? {
                    0 => Demand::None,
                    1 => Demand::Loaded,
                    2 => Demand::Ticking,
                    tag => return Err(DecodeError::UnknownTag(tag)),
                },
            },
            id::TICK => Message::Tick { tick: get_u64(b)? },
            id::RELAY => Message::Relay {
                from: get_u32(b)?,
                chunk: get_chunk(b)?,
                payload: get_bytes(b)?,
            },
            id::DIRECT_RELAY => Message::DirectRelay {
                from: get_u32(b)?,
                payload: get_bytes(b)?,
            },
            id::MASTER => Message::Master {
                scope: get_u32(b)?,
                master: get_u32(b)?,
            },
            id::BROADCAST_RELAY => Message::BroadcastRelay {
                from: get_u32(b)?,
                scope: get_u32(b)?,
                payload: get_bytes(b)?,
            },
            id::PLACEMENT => {
                let request_id = get_u32(b)?;
                let outcome = match get_u8(b)? {
                    0 => PlacementOutcome::Node {
                        node: get_u32(b)?,
                        addr: get_str(b)?,
                    },
                    1 => PlacementOutcome::Denied {
                        reason: get_str(b)?,
                    },
                    tag => return Err(DecodeError::UnknownTag(tag)),
                };
                Message::Placement {
                    request_id,
                    outcome,
                }
            }
            id::STATUS => Message::Status {
                request_id: get_u32(b)?,
                online: get_u32(b)?,
                capacity: get_u32(b)?,
            },
            id::NODE_UP => Message::NodeUp {
                node: get_u32(b)?,
                name: get_str(b)?,
            },
            id::NODE_DOWN => Message::NodeDown { node: get_u32(b)? },
            id::PLAYER_JOINED => Message::PlayerJoined {
                node: get_u32(b)?,
                uuid: get_uuid(b)?,
                name: get_str(b)?,
            },
            id::PLAYER_LEFT => Message::PlayerLeft {
                node: get_u32(b)?,
                uuid: get_uuid(b)?,
            },
            id::ID_BLOCK_REQUEST => Message::IdBlockRequest,
            id::COUNTER_BLOCK_REQUEST => Message::CounterBlockRequest {
                counter: get_u8(b)?,
                floor: get_u32(b)?,
            },
            id::TICK_INTERVAL => Message::TickInterval { nanos: get_u64(b)? },
            id::ANNOUNCE => Message::Announce {
                payload: get_bytes(b)?,
            },
            id::HANDOFF_REQUEST => Message::HandoffRequest {
                uuid: get_uuid(b)?,
                to: get_u32(b)?,
            },
            id::HANDOFF_READY => Message::HandoffReady { uuid: get_uuid(b)? },
            id::HANDOFF_STATE => Message::HandoffState {
                uuid: get_uuid(b)?,
                data: get_bytes(b)?,
                state: get_bytes(b)?,
            },
            id::HANDOFF_ABORT => Message::HandoffAbort { uuid: get_uuid(b)? },
            id::HANDOFF_DONE => Message::HandoffDone { uuid: get_uuid(b)? },
            id::HANDOFF_FAILED => Message::HandoffFailed { uuid: get_uuid(b)? },
            id::HANDOFF_FROZEN => Message::HandoffFrozen {
                uuid: get_uuid(b)?,
                frames: get_u64(b)?,
            },
            id::ID_BLOCK => Message::IdBlock { block: get_u32(b)? },
            id::COUNTER_BLOCK => Message::CounterBlock {
                counter: get_u8(b)?,
                block: get_u32(b)?,
            },
            id::ANNOUNCE_RELAY => Message::AnnounceRelay {
                from: get_u32(b)?,
                payload: get_bytes(b)?,
            },
            id::HANDOFF_PREPARE => Message::HandoffPrepare {
                uuid: get_uuid(b)?,
                token: get_u64(b)?,
                chunk: get_chunk(b)?,
            },
            id::HANDOFF_CUT => Message::HandoffCut {
                uuid: get_uuid(b)?,
                to: get_u32(b)?,
                dim: get_u32(b)?,
                frames: get_u64(b)?,
            },
            id::HANDOFF_ARRIVE => Message::HandoffArrive {
                uuid: get_uuid(b)?,
                data: get_bytes(b)?,
                state: get_bytes(b)?,
            },
            id::HANDOFF_CANCEL => Message::HandoffCancel { uuid: get_uuid(b)? },
            id::HANDOFF_DIAL => Message::HandoffDial {
                uuid: get_uuid(b)?,
                token: get_u64(b)?,
                addr: get_str(b)?,
            },
            id::HANDOFF_FREEZE => Message::HandoffFreeze { uuid: get_uuid(b)? },
            id::HANDOFF_SWITCH => Message::HandoffSwitch { uuid: get_uuid(b)? },
            other => return Err(DecodeError::UnknownMessage(other)),
        };
        if body.has_remaining() {
            return Err(DecodeError::Trailing(body.remaining()));
        }
        Ok(msg)
    }
}

/// Reads one frame body. Returns `None` if the peer closed the connection
/// cleanly between frames.
pub async fn read_frame<R: AsyncRead + Unpin>(r: &mut R) -> std::io::Result<Option<Bytes>> {
    let mut len = [0u8; 4];
    match r.read_exact(&mut len).await {
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let len = u32::from_be_bytes(len) as usize;
    if len == 0 || len > MAX_FRAME {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            format!("invalid frame length {len}"),
        ));
    }
    let mut body = BytesMut::zeroed(len);
    r.read_exact(&mut body).await?;
    Ok(Some(body.freeze()))
}

/// Reads and decodes one message. Returns `None` on clean end of stream.
pub async fn read_message<R: AsyncRead + Unpin>(r: &mut R) -> std::io::Result<Option<Message>> {
    match read_frame(r).await? {
        None => Ok(None),
        Some(body) => Message::decode(body)
            .map(Some)
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e)),
    }
}

pub async fn write_message<W: AsyncWrite + Unpin>(w: &mut W, msg: &Message) -> std::io::Result<()> {
    w.write_all(&msg.to_frame()).await
}
