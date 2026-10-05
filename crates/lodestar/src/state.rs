//! The cluster state machine.
//!
//! Everything here is synchronous and free of I/O: the server feeds in messages
//! and the time, and gets back a list of [`Effect`]s to carry out. That keeps
//! the rules about ticking, ownership and placement testable without sockets
//! or clocks.

use std::cmp::Reverse;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::time::{Duration, Instant};

use bytes::Bytes;
use lode_protocol::{
    CLUSTER_SCOPE, COUNTER_BLOCK_SHIFT, COUNTERS, ChunkKey, Demand, ID_BLOCK_SHIFT, Message,
    NO_NODE, NodeId, PROTOCOL_VERSION, PlacementOutcome, RegionKey, Role,
};
use tracing::{debug, info, warn};

use crate::zones;

mod checkpoint;
mod chunks;
mod clock;
mod configuration;
mod connections;
mod custody;
mod handoff;
mod masters;
mod messages;
mod player_data;
mod players;
mod regions;
mod saving;
mod world;

/// The first entity id block lodestar hands out. Block 0 has the ids nodes use
/// before they have a block of their own.
const FIRST_ID_BLOCK: u32 = 1;
/// The last block of positive `i32` ids, after which lodestar starts over.
const LAST_ID_BLOCK: u32 = (i32::MAX as u32) >> ID_BLOCK_SHIFT;

/// The last block of a counter that has only positive `i32` ids. Past it, every
/// request gets this block again.
const LAST_COUNTER_BLOCK: u32 = (i32::MAX as u32) >> COUNTER_BLOCK_SHIFT;
/// The longest tick the master of the cluster's state can ask for: `/tick`
/// goes no slower than one tick a second.
const LONGEST_TICK: Duration = Duration::from_secs(1);

/// How long a zone keeps its owner before it can be handed to a node that has
/// many more of the zone's players.
const REBALANCE_COOLDOWN_TICKS: u64 = 200;
/// A handover whose receiver has not caught up after this long is given up.
const HANDOVER_TIMEOUT_TICKS: u64 = 1200;
/// Ownership is looked at again at least this often, so that cooldowns run out
/// even when nothing else changes.
const REPLAN_INTERVAL_TICKS: u64 = 20;

pub type ConnId = u64;

#[derive(Debug)]
pub enum Effect {
    FreezeWorld,
    SnapshotRecord {
        conn: ConnId,
        request: crate::world::Request,
    },
    CommitWorld {
        revision: u64,
    },
    WorldRequest {
        conn: ConnId,
        request: crate::world::Request,
        committed: bool,
    },
    StoreGlobal {
        scope: String,
        payload: Bytes,
    },
    /// Queue an encoded frame on a connection.
    Send(ConnId, Bytes),
    /// Drop a connection once its queue has been flushed. The server answers
    /// by calling [`State::disconnect`].
    Close(ConnId),
    /// Write a player's data to storage, replacing what was there.
    StorePlayerData {
        uuid: u128,
        data: Bytes,
    },
    /// Write down the next entity id block to hand out.
    StoreIdBlocks {
        next: u32,
    },
    /// Write down the next block of each counter to hand out.
    StoreCounterBlocks {
        next: [u32; COUNTERS as usize],
    },
}

#[derive(Debug, Clone)]
pub struct Policy {
    pub token: String,
    pub heartbeat_timeout: Duration,
    pub reservation_ttl: Duration,
    pub mspt_limit: Duration,
    /// The shortest time between the starts of two ticks, unless the master
    /// of the cluster's state asks for another.
    pub tick_interval: Duration,
    /// Maximum simulation tick or checkpoint phase duration. A timeout fences
    /// all workers when durable world transactions are enabled.
    pub tick_timeout: Duration,
    /// Move connected players between nodes on lodestar's own initiative.
    pub auto_handoff: bool,
}

#[derive(Clone, Copy)]
enum Peer {
    Node(NodeId),
    Proxy,
    Storage(NodeId),
}

struct Node {
    conn: ConnId,
    name: String,
    game_addr: String,
    max_players: u32,
    players: HashSet<u128>,
    chunks: HashSet<ChunkKey>,
    /// The dimensions this node is a worker of.
    dims: Vec<u32>,
    mspt: Duration,
    last_heartbeat: Instant,
}

struct Dimension {
    name: String,
    /// Every node that has the dimension, in joining order. Any of them can be
    /// handed its regions.
    workers: Vec<NodeId>,
}

/// A region that some node has at least one chunk of loaded.
struct Region {
    /// The one node that simulates the region. Its copy of the region is the
    /// truth; every other node replicates it. [`NO_NODE`] until one is picked
    /// at the next tick boundary, for a new region or one whose owner left.
    owner: NodeId,
    /// The owner the dimension's workers were last told about.
    announced: NodeId,
    /// The node the region is being handed to. It loads the region's chunks
    /// and catches up with the owner's copy before it takes over.
    incoming: NodeId,
    /// The tick `owner` last changed.
    since: u64,
    /// The region's chunks that some node has loaded.
    chunks: HashSet<ChunkKey>,
}

#[derive(Default)]
struct Chunk {
    /// Every node that has the chunk loaded.
    subs: Vec<NodeId>,
    /// The subscribers that would tick the chunk if they owned its region, as
    /// opposed to merely having it loaded at the edge of what they simulate.
    ticking: Vec<NodeId>,
    /// The subscribers whose copy of the chunk is known to match the owner's.
    synced: Vec<NodeId>,
    /// The demand each node was last sent for this chunk. Nodes that are not
    /// listed were last sent `Demand::None`, if anything.
    sent: Vec<(NodeId, Demand)>,
}

/// Regions on their way from one owner to another. The receiver takes over
/// all of them at once, once it has a synced copy of every chunk in them.
struct Handover {
    from: NodeId,
    to: NodeId,
    regions: Vec<RegionKey>,
    started: u64,
}

/// Runs ticks in lockstep: tick `n + 1` starts only once every node has
/// finished tick `n`.
struct Clock {
    tick: u64,
    /// When the next tick is due. Ticks follow a fixed schedule rather than
    /// being spaced from the last one, so that late wake-ups (timers are only
    /// good to about 15 ms on some systems) even out instead of adding up.
    due: Option<Instant>,
    started: Option<Instant>,
    /// Nodes that have yet to finish the current tick.
    pending: HashSet<NodeId>,
    stats: TickStats,
}

/// How the last ticks went, summed until they are logged.
#[derive(Default)]
struct TickStats {
    since: Option<Instant>,
    first_tick: u64,
    /// Per node, the time from the start of a tick to its `TickDone`, and how
    /// often it was the last to finish.
    done: BTreeMap<NodeId, (Duration, u32)>,
    /// From the last `TickDone` of a tick to the start of the next.
    between: Duration,
    last_done: Option<Instant>,
}

struct Player {
    node: NodeId,
    name: String,
    /// Where the player is, once their node has said.
    at: Option<ChunkKey>,
    /// The proxy connection the player came in through, if lodestar placed
    /// them. Only such a player can be moved to another node.
    proxy: Option<ConnId>,
    /// The tick from which the player may be moved to another node.
    movable_from: u64,
}

struct Reservation {
    node: NodeId,
    proxy: ConnId,
    expires: Instant,
}

pub struct State {
    policy: Policy,
    peers: HashMap<ConnId, Peer>,
    nodes: HashMap<NodeId, Node>,
    next_node: NodeId,
    /// Indexed by dimension id.
    dims: Vec<Dimension>,
    chunks: HashMap<ChunkKey, Chunk>,
    regions: HashMap<RegionKey, Region>,
    /// The last owner of each region that nobody has loaded any more. Its
    /// world save is likely the most recent copy of the region, so it gets the
    /// region back if the region comes back into use.
    history: HashMap<RegionKey, NodeId>,
    /// Regions that went out of use after their owner was announced.
    retired: Vec<RegionKey>,
    handovers: Vec<Handover>,
    /// Set when something changed that bears on who should own what.
    replan: bool,
    last_plan: u64,
    clock: Clock,
    checkpoint: checkpoint::Checkpoint,
    players: HashMap<u128, Player>,
    /// Slots held for players the proxy is still handing over to a node.
    reservations: HashMap<u128, Reservation>,
    /// Objects some node other than their region's owner is the authority for.
    custody: HashMap<custody::ObjectKey, custody::Held>,
    /// Claims waiting for the owner's answer, by claimant and claim number.
    claims: HashMap<(NodeId, u32), custody::PendingClaim>,
    /// Every player's saved data, as last saved.
    player_data: HashMap<u128, Bytes>,
    /// The node that has each player's data, if any.
    data_custody: HashMap<u128, NodeId>,
    /// Nodes waiting for a player's data, in the order they asked.
    data_waiting: Vec<(NodeId, u128)>,
    /// Players being moved to another node.
    handoffs: HashMap<u128, handoff::Handoff>,
    last_handoff_plan: u64,
    /// The next block of entity ids to hand out.
    next_id_block: u32,
    /// The next block of each counter to hand out.
    next_counter_blocks: [u32; COUNTERS as usize],
    /// The shortest time between the starts of two ticks, as the master of
    /// the cluster's state last asked for.
    tick_interval: Duration,
    /// The node that keeps the state of each scope: [`CLUSTER_SCOPE`], or a
    /// dimension. See `masters.rs`.
    masters: HashMap<u32, NodeId>,
}

fn send(out: &mut Vec<Effect>, conn: ConnId, msg: &Message) {
    out.push(Effect::Send(conn, msg.to_frame()));
}

fn token_matches(expected: &str, given: &str) -> bool {
    let (a, b) = (expected.as_bytes(), given.as_bytes());
    // Constant time in the contents, so the token cannot be guessed byte by byte.
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

impl Chunk {
    /// What the other nodes need of this chunk from `holder`, the owner of its
    /// region or the node the region is being handed to.
    fn demand(&self, holder: NodeId) -> Demand {
        if self.ticking.iter().any(|n| *n != holder) {
            Demand::Ticking
        } else if self.subs.iter().any(|n| *n != holder) {
            Demand::Loaded
        } else {
            Demand::None
        }
    }
}

impl Region {
    fn new(chunk: ChunkKey) -> Region {
        Region {
            owner: NO_NODE,
            announced: NO_NODE,
            incoming: NO_NODE,
            since: 0,
            chunks: HashSet::from([chunk]),
        }
    }
}
