//! Moving a connected player to another node without their client noticing.
//!
//! The client stays connected to the proxy throughout; what changes is which
//! node the proxy relays it to. A move goes through these steps, each started
//! by the answer to the one before:
//!
//! 1. **Preparing.** The node the player moves to, the *target*, is told to
//!    get ready (`HandoffPrepare`): it loads the world around the player and
//!    catches up with it, as it would for a player of its own. Meanwhile the
//!    proxy logs the player in there too (`HandoffDial`) and keeps that
//!    connection waiting. Once it has both, the target says so
//!    (`HandoffReady`).
//! 2. **Freezing.** The proxy stops passing on what the client sends
//!    (`HandoffFreeze`) and says how many packets it has written to the node
//!    the player is on, the *source* (`HandoffFrozen`).
//! 3. **Cutting.** The source handles those packets, and at the end of a tick
//!    lets go of the player (`HandoffCut`): it sends the client the last of
//!    what it had for it, hangs up on the proxy, and keeps the player as a
//!    mirror, like any remote player. It hands lodestar the player's data and
//!    what their client was last sent (`HandoffState`).
//! 4. **Arriving.** Before the next tick, lodestar makes the target the
//!    player's home and the holder of their data, and passes on the state
//!    (`HandoffArrive`). At the start of that tick the target turns its mirror
//!    of the player into a player of its own, on the proxy's waiting
//!    connection, and says so (`HandoffDone`).
//! 5. The proxy relays the client to the new connection once the source has
//!    hung up (`HandoffSwitch`), starting with what it held back.
//!
//! Until the source has let go, a move can be called off and the player stays
//! where they are. The source decides: called off while it may be letting go,
//! lodestar asks it (`HandoffCancel`) and waits for its answer before telling
//! the proxy to carry on.
//!
//! lodestar moves players for two reasons, between ticks. To home a player
//! on the node that simulates where they are, so that whatever they do there
//! is done on their own node, as long as that keeps every node within its
//! fair share of players. And to even out the players across nodes when a
//! node has drifted out of that range: after a node joins, or once the
//! players of one node have left, or off a node that ticks too slowly.

use super::*;
use tracing::debug;

/// How long the target has to load and catch up with the world around the
/// player. Less than the 30 seconds a node waits for a login to finish, which
/// the proxy's waiting connection is.
const PREPARE_TIMEOUT_TICKS: u64 = 500;
/// How long each later step may take.
const STEP_TIMEOUT_TICKS: u64 = 100;
/// A player who has just joined or moved stays where they are this long.
pub(super) const SETTLE_TICKS: u64 = 600;
/// After a move that did not happen, how long before the player is tried again.
const RETRY_TICKS: u64 = 1200;
/// Moves under way at once, across the cluster and per node.
const MAX_IN_FLIGHT: usize = 4;
const MAX_IN_FLIGHT_PER_NODE: usize = 2;
/// How often lodestar looks for players to move.
const PLAN_INTERVAL_TICKS: u64 = 20;
/// How far, as a share of its fair share, a node's player count may drift
/// before players are moved to even it out. Never less than one player.
const SLACK: f64 = 0.1;

pub(super) struct Handoff {
    pub(super) from: NodeId,
    pub(super) to: NodeId,
    /// The proxy connection the player came in through.
    pub(super) proxy: ConnId,
    stage: Stage,
    /// The tick the current stage began.
    since: u64,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Stage {
    Preparing,
    Freezing,
    Cutting,
    /// Asked the source not to let go of the player after all; waiting for
    /// it to say whether it already had.
    Cancelling,
    Arriving,
}

impl Stage {
    fn timeout(self) -> u64 {
        match self {
            Stage::Preparing => PREPARE_TIMEOUT_TICKS,
            _ => STEP_TIMEOUT_TICKS,
        }
    }
}

/// Players per node, counting the moves under way as done, and each node's
/// fair share of them, in proportion to its capacity.
struct Shares {
    load: BTreeMap<NodeId, f64>,
    ideal: BTreeMap<NodeId, f64>,
    busy: BTreeMap<NodeId, usize>,
}

impl Shares {
    fn excess(&self, node: NodeId) -> f64 {
        self.load[&node] - self.ideal[&node]
    }

    fn slack(&self, node: NodeId) -> f64 {
        (self.ideal[&node] * SLACK).max(1.0)
    }

    fn busy(&self, node: NodeId) -> usize {
        self.busy.get(&node).copied().unwrap_or(0)
    }

    fn moved(&mut self, from: NodeId, to: NodeId) {
        *self.load.get_mut(&from).expect("a counted node") -= 1.0;
        *self.load.get_mut(&to).expect("a counted node") += 1.0;
        *self.busy.entry(from).or_default() += 1;
        *self.busy.entry(to).or_default() += 1;
    }
}

impl State {
    /// Starts moving a player to `to`. Returns whether it could.
    fn start_handoff(&mut self, uuid: u128, to: NodeId, out: &mut Vec<Effect>) -> bool {
        let Some(player) = self.players.get(&uuid) else {
            return false;
        };
        let (Some(proxy), Some(at)) = (player.proxy, player.at) else {
            return false;
        };
        let from = player.node;
        if from == to
            || self.handoffs.contains_key(&uuid)
            || !self.peers.contains_key(&proxy)
            || !self.is_worker(to, at.dim)
        {
            return false;
        }
        let Some(target) = self.nodes.get(&to) else {
            return false;
        };
        let token = rand::random::<u64>();
        info!(
            "moving {} from node #{from} to node #{to}",
            self.player_name(uuid)
        );
        send(
            out,
            target.conn,
            &Message::HandoffPrepare {
                uuid,
                token,
                chunk: at,
            },
        );
        send(
            out,
            proxy,
            &Message::HandoffDial {
                uuid,
                token,
                addr: target.game_addr.clone(),
            },
        );
        self.handoffs.insert(
            uuid,
            Handoff {
                from,
                to,
                proxy,
                stage: Stage::Preparing,
                since: self.clock.tick,
            },
        );
        true
    }

    /// A node asks for a player to be moved, as an operator's command does.
    pub(super) fn handoff_request(
        &mut self,
        node: NodeId,
        uuid: u128,
        to: NodeId,
        out: &mut Vec<Effect>,
    ) {
        if !self.start_handoff(uuid, to, out) {
            warn!(
                "node #{node} asked to move {} to node #{to}, which cannot be done now",
                self.player_name(uuid)
            );
        }
    }

    pub(super) fn handoff_ready(&mut self, node: NodeId, uuid: u128, out: &mut Vec<Effect>) {
        let tick = self.clock.tick;
        let Some(handoff) = self
            .handoffs
            .get_mut(&uuid)
            .filter(|h| h.to == node && h.stage == Stage::Preparing)
        else {
            return;
        };
        handoff.stage = Stage::Freezing;
        handoff.since = tick;
        debug!("node #{node} is ready for {uuid:032x}");
        send(out, handoff.proxy, &Message::HandoffFreeze { uuid });
    }

    pub(super) fn handoff_frozen(
        &mut self,
        conn: ConnId,
        uuid: u128,
        frames: u64,
        out: &mut Vec<Effect>,
    ) {
        let tick = self.clock.tick;
        let dim = self.players.get(&uuid).and_then(|p| p.at).map(|at| at.dim);
        let Some(handoff) = self
            .handoffs
            .get_mut(&uuid)
            .filter(|h| h.proxy == conn && h.stage == Stage::Freezing)
        else {
            return;
        };
        let (Some(dim), Some(source)) = (dim, self.nodes.get(&handoff.from)) else {
            return self.call_off(uuid, out);
        };
        handoff.stage = Stage::Cutting;
        handoff.since = tick;
        debug!("the proxy froze {uuid:032x} after {frames} packets");
        let cut = Message::HandoffCut {
            uuid,
            to: handoff.to,
            dim,
            frames,
        };
        send(out, source.conn, &cut);
    }

    /// The source has let go of the player. Whatever else happens, their data
    /// as it sent it is the newest there is.
    pub(super) fn handoff_state(
        &mut self,
        node: NodeId,
        uuid: u128,
        data: Bytes,
        state: Bytes,
        out: &mut Vec<Effect>,
    ) {
        let tick = self.clock.tick;
        self.player_data.insert(uuid, data.clone());
        out.push(Effect::StorePlayerData {
            uuid,
            data: data.clone(),
        });

        let handoff = self.handoffs.get(&uuid).filter(|h| h.from == node);
        let target = handoff
            .filter(|h| h.stage == Stage::Cutting)
            .and_then(|h| self.nodes.get(&h.to).map(|n| (h.to, n.conn)));
        let Some((to, target_conn)) = target else {
            // A move that was called off, or whose target has gone, but the
            // source let go of the player all the same. They are gone.
            warn!(
                "node #{node} let go of {} after the move was called off",
                self.player_name(uuid)
            );
            if self.data_custody.get(&uuid) == Some(&node) {
                self.data_custody.remove(&uuid);
                self.lend_to_next(uuid, out);
            }
            if let Some(handoff) = self.handoffs.remove(&uuid) {
                self.cancel_target_and_proxy(uuid, &handoff, out);
            }
            if self.players.get(&uuid).is_some_and(|p| p.node == node) {
                self.player_leave(uuid, out);
            }
            return;
        };

        // The player and their data move together, before the next tick.
        self.data_custody.insert(uuid, to);
        let Some(player) = self.players.get_mut(&uuid) else {
            return;
        };
        player.node = to;
        player.movable_from = tick + SETTLE_TICKS;
        let name = player.name.clone();
        self.node_mut(node).players.remove(&uuid);
        self.node_mut(to).players.insert(uuid);
        self.replan = true;
        self.broadcast(
            &Message::PlayerJoined {
                node: to,
                uuid,
                name,
            },
            out,
        );
        send(
            out,
            target_conn,
            &Message::HandoffArrive { uuid, data, state },
        );
        let handoff = self.handoffs.get_mut(&uuid).expect("looked up above");
        handoff.stage = Stage::Arriving;
        handoff.since = tick;
    }

    pub(super) fn handoff_done(&mut self, node: NodeId, uuid: u128, out: &mut Vec<Effect>) {
        if !self
            .handoffs
            .get(&uuid)
            .is_some_and(|h| h.to == node && h.stage == Stage::Arriving)
        {
            return;
        }
        let handoff = self.handoffs.remove(&uuid).expect("just looked up");
        info!(
            "{} moved from node #{} to node #{node}",
            self.player_name(uuid),
            handoff.from
        );
        if self.peers.contains_key(&handoff.proxy) {
            send(out, handoff.proxy, &Message::HandoffSwitch { uuid });
        }
    }

    pub(super) fn handoff_abort(&mut self, node: NodeId, uuid: u128, out: &mut Vec<Effect>) {
        let Some(handoff) = self.handoffs.get(&uuid) else {
            return;
        };
        let stage = handoff.stage;
        debug!("node #{node} gives up moving {uuid:032x} while {stage:?}");
        if node == handoff.from {
            if stage != Stage::Arriving {
                info!("node #{node} keeps {} for now", self.player_name(uuid));
                self.call_off(uuid, out);
            }
        } else if node == handoff.to {
            match stage {
                Stage::Preparing | Stage::Freezing => self.call_off(uuid, out),
                Stage::Cutting => self.cancel_cut(uuid, out),
                Stage::Cancelling => {}
                Stage::Arriving => self.fail_arrival(uuid, out),
            }
        }
    }

    pub(super) fn handoff_failed(&mut self, conn: ConnId, uuid: u128, out: &mut Vec<Effect>) {
        let Some(handoff) = self.handoffs.get(&uuid).filter(|h| h.proxy == conn) else {
            return;
        };
        debug!(
            "the proxy cannot move {uuid:032x} while {:?}",
            handoff.stage
        );
        match handoff.stage {
            Stage::Preparing | Stage::Freezing => self.call_off(uuid, out),
            Stage::Cutting => self.cancel_cut(uuid, out),
            // Past the point of no return: the target takes the player over,
            // and they leave from there if their connection is gone.
            Stage::Cancelling | Stage::Arriving => {}
        }
    }

    /// Calls off a move that the source has not been asked to make, or has
    /// said it will not make. The player stays where they are.
    fn call_off(&mut self, uuid: u128, out: &mut Vec<Effect>) {
        let Some(handoff) = self.handoffs.remove(&uuid) else {
            return;
        };
        info!(
            "moving {} to node #{} is called off",
            self.player_name(uuid),
            handoff.to
        );
        self.cancel_target_and_proxy(uuid, &handoff, out);
        let retry = self.clock.tick + RETRY_TICKS;
        if let Some(player) = self.players.get_mut(&uuid) {
            player.movable_from = player.movable_from.max(retry);
        }
    }

    /// Tells the target and the proxy that a move is off, where they are still there.
    fn cancel_target_and_proxy(&self, uuid: u128, handoff: &Handoff, out: &mut Vec<Effect>) {
        let cancel = Message::HandoffCancel { uuid };
        if let Some(target) = self.nodes.get(&handoff.to) {
            send(out, target.conn, &cancel);
        }
        if self.peers.contains_key(&handoff.proxy) {
            send(out, handoff.proxy, &cancel);
        }
    }

    /// The source may be letting go of the player as it is: ask it not to,
    /// and wait for its answer.
    fn cancel_cut(&mut self, uuid: u128, out: &mut Vec<Effect>) {
        let tick = self.clock.tick;
        let Some(handoff) = self
            .handoffs
            .get_mut(&uuid)
            .filter(|h| h.stage == Stage::Cutting)
        else {
            return;
        };
        let Some(source) = self.nodes.get(&handoff.from) else {
            return self.call_off(uuid, out);
        };
        handoff.stage = Stage::Cancelling;
        handoff.since = tick;
        send(out, source.conn, &Message::HandoffCancel { uuid });
    }

    /// The target could not take over a player the source has let go of.
    /// Their client has nowhere to go: they leave.
    fn fail_arrival(&mut self, uuid: u128, out: &mut Vec<Effect>) {
        let Some(handoff) = self.handoffs.remove(&uuid) else {
            return;
        };
        warn!(
            "node #{} could not take over {}, who leaves",
            handoff.to,
            self.player_name(uuid)
        );
        if self.data_custody.get(&uuid) == Some(&handoff.to) {
            self.data_custody.remove(&uuid);
            self.lend_to_next(uuid, out);
        }
        if self.peers.contains_key(&handoff.proxy) {
            send(out, handoff.proxy, &Message::HandoffCancel { uuid });
        }
        self.player_leave(uuid, out);
    }

    /// A player left in the middle of a move.
    pub(super) fn forget_handoff_of(&mut self, uuid: u128, out: &mut Vec<Effect>) {
        if let Some(handoff) = self.handoffs.remove(&uuid) {
            self.cancel_target_and_proxy(uuid, &handoff, out);
        }
    }

    /// A node left: moves to it or from it are called off where they can be.
    pub(super) fn forget_handoffs_of_node(&mut self, node: NodeId, out: &mut Vec<Effect>) {
        let mut affected: Vec<(u128, Stage, bool)> = self
            .handoffs
            .iter()
            .filter(|(_, h)| h.from == node || h.to == node)
            .map(|(uuid, h)| (*uuid, h.stage, h.from == node))
            .collect();
        affected.sort_unstable_by_key(|(uuid, ..)| *uuid);
        for (uuid, stage, was_source) in affected {
            match (was_source, stage) {
                // The source's part is done.
                (true, Stage::Arriving) => {}
                // The player was on the source, and is gone with it.
                (true, _) => self.call_off(uuid, out),
                (false, Stage::Preparing | Stage::Freezing) => self.call_off(uuid, out),
                (false, Stage::Cutting) => self.cancel_cut(uuid, out),
                (false, Stage::Cancelling) => {}
                // The player was the target's, and is gone with it.
                (false, Stage::Arriving) => {
                    if let Some(handoff) = self.handoffs.remove(&uuid)
                        && self.peers.contains_key(&handoff.proxy)
                    {
                        send(out, handoff.proxy, &Message::HandoffCancel { uuid });
                    }
                }
            }
        }
    }

    /// A proxy left: its players' connections are gone, and so is any reason
    /// to move them.
    pub(super) fn forget_handoffs_of_proxy(&mut self, conn: ConnId, out: &mut Vec<Effect>) {
        let mut affected: Vec<(u128, Stage)> = self
            .handoffs
            .iter()
            .filter(|(_, h)| h.proxy == conn)
            .map(|(uuid, h)| (*uuid, h.stage))
            .collect();
        affected.sort_unstable_by_key(|(uuid, _)| *uuid);
        for (uuid, stage) in affected {
            match stage {
                Stage::Preparing | Stage::Freezing => self.call_off(uuid, out),
                Stage::Cutting => self.cancel_cut(uuid, out),
                Stage::Cancelling | Stage::Arriving => {}
            }
        }
    }

    /// Gives up on the steps that have taken too long.
    pub(super) fn expire_handoffs(&mut self, out: &mut Vec<Effect>) {
        let tick = self.clock.tick;
        let mut expired: Vec<(u128, Stage)> = self
            .handoffs
            .iter()
            .filter(|(_, h)| tick >= h.since + h.stage.timeout())
            .map(|(uuid, h)| (*uuid, h.stage))
            .collect();
        expired.sort_unstable_by_key(|(uuid, _)| *uuid);
        for (uuid, stage) in expired {
            warn!(
                "moving {} took too long while {stage:?}, giving up",
                self.player_name(uuid)
            );
            match stage {
                Stage::Preparing | Stage::Freezing | Stage::Cancelling => self.call_off(uuid, out),
                Stage::Cutting => self.cancel_cut(uuid, out),
                Stage::Arriving => self.fail_arrival(uuid, out),
            }
        }
    }

    /// Whether a node can take one more player in a dimension now.
    fn can_take(&self, node: NodeId, dim: u32, now: Instant) -> bool {
        let Some(n) = self.nodes.get(&node) else {
            return false;
        };
        let incoming = self.handoffs.values().filter(|h| h.to == node).count();
        self.is_healthy(n, now)
            && n.mspt <= self.policy.mspt_limit
            && self.is_worker(node, dim)
            && n.players.len() + incoming < n.max_players as usize
    }

    fn shares(&self, now: Instant) -> Shares {
        let mut shares = Shares {
            load: BTreeMap::new(),
            ideal: BTreeMap::new(),
            busy: BTreeMap::new(),
        };
        let mut capacity = BTreeMap::new();
        for (id, node) in &self.nodes {
            if !self.is_healthy(node, now) {
                continue;
            }
            shares.load.insert(*id, node.players.len() as f64);
            // A node that ticks too slowly is not given players, and so is
            // eased of the ones it has.
            let usable = if node.mspt > self.policy.mspt_limit {
                0.0
            } else {
                f64::from(node.max_players)
            };
            capacity.insert(*id, usable);
        }
        for handoff in self.handoffs.values() {
            for node in [handoff.from, handoff.to] {
                *shares.busy.entry(node).or_default() += 1;
            }
            if shares.load.contains_key(&handoff.from) && shares.load.contains_key(&handoff.to) {
                *shares.load.get_mut(&handoff.from).expect("checked") -= 1.0;
                *shares.load.get_mut(&handoff.to).expect("checked") += 1.0;
            }
        }
        let players: f64 = shares.load.values().sum();
        let total: f64 = capacity.values().sum();
        for (id, cap) in capacity {
            let ideal = if total > 0.0 {
                players * cap / total
            } else {
                0.0
            };
            shares.ideal.insert(id, ideal);
        }
        shares
    }

    /// Looks for players to move, and starts moving them.
    pub(super) fn plan_handoffs(&mut self, now: Instant, out: &mut Vec<Effect>) {
        let tick = self.clock.tick;
        if !self.policy.auto_handoff || tick < self.last_handoff_plan + PLAN_INTERVAL_TICKS {
            return;
        }
        self.last_handoff_plan = tick;
        if self.handoffs.len() >= MAX_IN_FLIGHT {
            return;
        }
        let mut shares = self.shares(now);
        let mut candidates: Vec<u128> = self
            .players
            .iter()
            .filter(|(uuid, p)| {
                p.at.is_some()
                    && p.proxy.is_some_and(|proxy| self.peers.contains_key(&proxy))
                    && tick >= p.movable_from
                    && !self.handoffs.contains_key(uuid)
                    && shares.load.contains_key(&p.node)
            })
            .map(|(uuid, _)| *uuid)
            .collect();
        candidates.sort_unstable();
        let owner_of = |state: &State, uuid: u128| {
            let at = state.players[&uuid].at.expect("a candidate has a position");
            state.regions.get(&at.region()).map_or(NO_NODE, |r| r.owner)
        };

        // Home players on the node that simulates where they are, while that
        // keeps both nodes within their fair share.
        for &uuid in &candidates {
            if self.handoffs.len() >= MAX_IN_FLIGHT {
                return;
            }
            let player = &self.players[&uuid];
            let (from, dim) = (player.node, player.at.expect("a candidate").dim);
            let owner = owner_of(self, uuid);
            if owner == NO_NODE
                || owner == from
                || !shares.load.contains_key(&owner)
                || !self.can_take(owner, dim, now)
                || shares.busy(from) >= MAX_IN_FLIGHT_PER_NODE
                || shares.busy(owner) >= MAX_IN_FLIGHT_PER_NODE
                || shares.excess(owner) + 1.0 > shares.slack(owner)
                || shares.excess(from) - 1.0 < -shares.slack(from)
            {
                continue;
            }
            if self.start_handoff(uuid, owner, out) {
                shares.moved(from, owner);
            }
        }

        // Even out the nodes that have drifted out of their fair share.
        while self.handoffs.len() < MAX_IN_FLIGHT {
            let nodes: Vec<NodeId> = shares.load.keys().copied().collect();
            let source = nodes
                .iter()
                .copied()
                .filter(|n| shares.busy(*n) < MAX_IN_FLIGHT_PER_NODE)
                .max_by(|a, b| {
                    let key = |n: NodeId| shares.excess(n) - shares.slack(n);
                    key(*a).total_cmp(&key(*b)).then(b.cmp(a))
                });
            let target = nodes
                .iter()
                .copied()
                .filter(|n| shares.busy(*n) < MAX_IN_FLIGHT_PER_NODE && shares.ideal[n] > 0.0)
                .min_by(|a, b| {
                    let key = |n: NodeId| shares.excess(n) + shares.slack(n);
                    key(*a).total_cmp(&key(*b)).then(a.cmp(b))
                });
            let (Some(source), Some(target)) = (source, target) else {
                break;
            };
            let drifted = shares.excess(source) > shares.slack(source)
                || shares.excess(target) < -shares.slack(target);
            if source == target || !drifted || shares.excess(source) - shares.excess(target) <= 1.0
            {
                break;
            }
            // Rather someone the target simulates the surroundings of, and
            // rather not someone the source does.
            let choice = candidates
                .iter()
                .copied()
                .filter(|uuid| {
                    let player = &self.players[uuid];
                    player.node == source
                        && !self.handoffs.contains_key(uuid)
                        && self.can_take(target, player.at.expect("a candidate").dim, now)
                })
                .max_by_key(|uuid| {
                    let owner = owner_of(self, *uuid);
                    (owner == target, owner != source, Reverse(*uuid))
                });
            let Some(uuid) = choice else {
                break;
            };
            if !self.start_handoff(uuid, target, out) {
                break;
            }
            shares.moved(source, target);
        }
    }
}
