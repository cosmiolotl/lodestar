//! Drain all inter-worker actions before taking an atomic world revision.
use super::*;
use bytes::{Buf, BufMut, BytesMut};

pub const TAG: u8 = 241;
const DRAIN: u8 = 0;
const SNAPSHOT: u8 = 1;
const RESUME: u8 = 2;
const STOP: u8 = 3;
const STOPPED: u8 = 4;
const CAPTURED: u8 = 5;
const COMMITTED: u8 = 6;
const RETRY: u8 = 7;
const UPLOADING: u8 = 7;
const INTERVAL: u64 = 20;

/// Ticks between world checkpoints. `LODESTAR_CHECKPOINT_INTERVAL` overrides
/// it, to measure what checkpoints cost; a longer interval widens the window
/// of play a failure rolls back.
fn interval() -> u64 {
    static INTERVAL_OVERRIDE: std::sync::OnceLock<u64> = std::sync::OnceLock::new();
    *INTERVAL_OVERRIDE.get_or_init(|| {
        std::env::var("LODESTAR_CHECKPOINT_INTERVAL")
            .ok()
            .and_then(|v| v.parse().ok())
            .filter(|&v| v > 1)
            .unwrap_or(INTERVAL)
    })
}

#[derive(Default)]
pub(super) struct Checkpoint {
    enabled: bool,
    revision: u64,
    epoch: u64,
    finished_tick: u64,
    participants: HashSet<NodeId>,
    tick_participants: HashSet<NodeId>,
    uploads: HashSet<NodeId>,
    departing: HashSet<NodeId>,
    committing: bool,
    pub(super) authority: saving::SaveAuthority,
    pending: HashSet<NodeId>,
    phase: Option<u8>,
    round: u32,
    dirty: bool,
    stopping: HashSet<NodeId>,
    clean: HashSet<ConnId>,
    /// When the current checkpoint's first drain, and its snapshot, began.
    began: Option<Instant>,
    snapshot_began: Option<Instant>,
}

impl State {
    pub fn enable_checkpoints(&mut self, revision: u64, epoch: u64) {
        self.checkpoint.enabled = true;
        self.checkpoint.epoch = epoch;
        self.checkpoint.revision = revision;
    }

    pub fn unexpected_worker_loss(&self, conn: ConnId) -> bool {
        self.checkpoint.enabled
            && match self.peers.get(&conn) {
                Some(Peer::Node(_)) => true,
                Some(Peer::Storage(node)) => {
                    self.nodes.contains_key(node)
                        && !self.checkpoint.clean.contains(&self.nodes[node].conn)
                }
                _ => false,
            }
            && !self.checkpoint.clean.contains(&conn)
    }

    pub(super) fn current_worker_epoch(&self, name: &str) -> bool {
        name.strip_prefix("txn2:")
            .and_then(|name| name.split_once(':'))
            .and_then(|(epoch, _)| epoch.parse::<u64>().ok())
            == Some(self.checkpoint.epoch)
    }

    pub(super) fn requires_checkpoints(&self) -> bool {
        self.checkpoint.enabled
    }

    pub(super) fn checkpoint_traffic(&mut self, conn: ConnId, msg: &Message) -> bool {
        // After the final revision, a departing node may only close its connection.
        if self.checkpoint.clean.contains(&conn) {
            return false;
        }
        if matches!(self.checkpoint.phase, Some(DRAIN | SNAPSHOT))
            && matches!(
                msg,
                Message::Publish { .. }
                    | Message::Direct { to: 1.., .. }
                    | Message::Broadcast { .. }
                    | Message::Claim { .. }
                    | Message::ClaimAnswer { .. }
                    | Message::Release { .. }
                    | Message::HandoffState { .. }
                    | Message::HandoffDone { .. }
            )
        {
            self.checkpoint.dirty = true;
        }
        true
    }

    pub(super) fn checkpoint_ack(
        &mut self,
        conn: ConnId,
        mut payload: Bytes,
        out: &mut Vec<Effect>,
    ) {
        let (node, storage) = match self.peers.get(&conn).copied() {
            Some(Peer::Node(node)) => (node, false),
            Some(Peer::Storage(node)) => (node, true),
            _ => return,
        };
        if !self.checkpoint.enabled || payload.len() != 22 {
            out.push(Effect::Close(conn));
            return;
        }
        payload.advance(1);
        let phase = payload.get_u8();
        let epoch = payload.get_u64();
        let revision = payload.get_u64();
        let round = payload.get_u32();
        if phase == STOP && !storage {
            self.checkpoint.stopping.insert(node);
            return;
        }
        if epoch != self.checkpoint.epoch
            || revision != self.checkpoint.revision + 1
            || round != self.checkpoint.round
        {
            return;
        }
        if storage {
            if phase == SNAPSHOT && self.accepts_snapshot(node, epoch, revision, round) {
                self.checkpoint.uploads.remove(&node);
            }
        } else if self.checkpoint.phase == Some(DRAIN) && phase == DRAIN {
            self.checkpoint.pending.remove(&node);
        } else if self.checkpoint.phase == Some(SNAPSHOT) && matches!(phase, SNAPSHOT | CAPTURED) {
            self.checkpoint.pending.remove(&node);
            // Legacy txn1 workers upload before acknowledging their snapshot.
            if phase == SNAPSHOT {
                self.checkpoint.uploads.remove(&node);
            }
        }
        // Old acknowledgements cannot satisfy a newer phase, round or revision.
    }

    pub(super) fn checkpoint_started_tick(&mut self) {
        self.checkpoint.tick_participants = self.clock.pending.clone();
    }

    /// Returns true while the clock must stay behind the checkpoint barrier.
    pub(super) fn checkpoint_step(&mut self, now: Instant, out: &mut Vec<Effect>) -> bool {
        if !self.checkpoint.enabled || self.clock.tick == self.checkpoint.finished_tick {
            return false;
        }
        if matches!(self.checkpoint.phase, Some(DRAIN | SNAPSHOT))
            && !self.checkpoint.pending.is_empty()
        {
            if now >= self.clock.started.unwrap() + self.policy.tick_timeout {
                for node in &self.checkpoint.pending {
                    out.push(Effect::Close(self.nodes[node].conn));
                }
            }
            return true;
        }
        if self.checkpoint.phase == Some(SNAPSHOT) && self.checkpoint.dirty {
            // The old upload must finish before its round is retired. A worker can
            // then merge its detached records back into the next capture safely.
            if !self.checkpoint.uploads.is_empty() {
                if now >= self.clock.started.unwrap() + self.policy.tick_timeout {
                    for node in &self.checkpoint.uploads {
                        out.push(Effect::Close(self.nodes[node].conn));
                    }
                }
                return true;
            }
            self.checkpoint_notify(RETRY, self.checkpoint.revision + 1, out);
        }
        match self.checkpoint.phase {
            Some(UPLOADING) => {
                self.resume_tick(out);
                return false;
            }
            None if self.clock.tick % interval() != 1 && self.checkpoint.stopping.is_empty() => {
                self.resume_tick(out);
                return false;
            }
            None => {
                self.checkpoint.participants = self.checkpoint.tick_participants.clone();
                self.checkpoint.round = 0;
                self.checkpoint.began = Some(now);
            }
            Some(DRAIN) if !self.checkpoint.dirty => {
                self.checkpoint.snapshot_began = Some(now);
                self.checkpoint.authority = self.capture_save_authority();
                self.checkpoint.uploads = self.checkpoint.participants.clone();
                self.checkpoint_phase(SNAPSHOT, now, out);
                return true;
            }
            Some(SNAPSHOT) if !self.checkpoint.dirty => {
                if let (Some(began), Some(snapshot)) =
                    (self.checkpoint.began, self.checkpoint.snapshot_began)
                {
                    debug!(
                        "checkpoint {}: {} drain rounds in {:?}, snapshot in {:?}",
                        self.checkpoint.revision + 1,
                        self.checkpoint.round,
                        snapshot - began,
                        now - snapshot,
                    );
                }
                // Freeze the transaction's control-stream changes before permitting any
                // later tick to produce journal entries or player lifecycle saves.
                out.push(Effect::FreezeWorld);
                self.checkpoint.phase = Some(UPLOADING);
                self.checkpoint.departing = std::mem::take(&mut self.checkpoint.stopping);
                self.resume_tick(out);
                return false;
            }
            _ => {}
        }
        self.checkpoint.round += 1;
        if self.checkpoint.round > 64 {
            warn!("world checkpoint did not quiesce; stopping the cluster");
            for node in &self.checkpoint.participants {
                out.push(Effect::Close(self.nodes[node].conn));
            }
            return true;
        }
        self.checkpoint_phase(DRAIN, now, out);
        true
    }

    fn checkpoint_phase(&mut self, phase: u8, now: Instant, out: &mut Vec<Effect>) {
        self.checkpoint.phase = Some(phase);
        self.checkpoint.dirty = false;
        self.checkpoint.pending = self.checkpoint.participants.clone();
        self.clock.started = Some(now);
        self.checkpoint_notify(phase, self.checkpoint.revision + 1, out);
    }

    fn resume_tick(&mut self, out: &mut Vec<Effect>) {
        for node in &self.checkpoint.tick_participants {
            self.checkpoint_send(*node, RESUME, self.checkpoint.revision, out);
        }
        self.checkpoint.finished_tick = self.clock.tick;
    }

    pub(super) fn valid_save_binding(&self, node: NodeId, epoch: u64) -> bool {
        self.checkpoint.enabled
            && epoch == self.checkpoint.epoch
            && self.nodes.contains_key(&node)
            && !self.checkpoint.clean.contains(&self.nodes[&node].conn)
            && !self
                .peers
                .values()
                .any(|peer| matches!(peer, Peer::Storage(id) if *id == node))
    }

    pub(super) fn accepts_snapshot(
        &self,
        node: NodeId,
        epoch: u64,
        revision: u64,
        round: u32,
    ) -> bool {
        epoch == self.checkpoint.epoch
            && revision == self.checkpoint.revision + 1
            && round == self.checkpoint.round
            && self.checkpoint.uploads.contains(&node)
            && matches!(self.checkpoint.phase, Some(SNAPSHOT | UPLOADING))
            && !self.checkpoint.committing
    }

    pub(super) fn poll_upload(&mut self, now: Instant, out: &mut Vec<Effect>) {
        if self.checkpoint.phase != Some(UPLOADING) {
            return;
        }
        if self.checkpoint.uploads.is_empty() && !self.checkpoint.committing {
            self.checkpoint.committing = true;
            out.push(Effect::CommitWorld {
                revision: self.checkpoint.revision + 1,
            });
        }
        if now >= self.checkpoint.snapshot_began.unwrap() + self.policy.tick_timeout {
            for node in &self.checkpoint.participants {
                out.push(Effect::Close(self.nodes[node].conn));
            }
        }
    }

    pub fn world_committed(&mut self, revision: u64, out: &mut Vec<Effect>) {
        assert!(self.checkpoint.committing && revision == self.checkpoint.revision + 1);
        self.checkpoint.revision = revision;
        self.checkpoint.committing = false;
        self.checkpoint.phase = None;
        self.checkpoint_notify(COMMITTED, revision, out);
        for node in std::mem::take(&mut self.checkpoint.departing) {
            self.checkpoint_send(node, STOPPED, revision, out);
            let conn = self.nodes[&node].conn;
            self.checkpoint.clean.insert(conn);
            self.disconnect(conn, out);
        }
    }

    fn checkpoint_notify(&self, phase: u8, revision: u64, out: &mut Vec<Effect>) {
        for node in &self.checkpoint.participants {
            self.checkpoint_send(*node, phase, revision, out);
        }
    }

    fn checkpoint_send(&self, node: NodeId, phase: u8, revision: u64, out: &mut Vec<Effect>) {
        let Some(node) = self.nodes.get(&node) else {
            return;
        };
        let mut payload = BytesMut::with_capacity(22);
        payload.put_u8(TAG);
        payload.put_u8(phase);
        payload.put_u64(self.checkpoint.epoch);
        payload.put_u64(revision);
        payload.put_u32(self.checkpoint.round);
        send(
            out,
            node.conn,
            &Message::DirectRelay {
                from: NO_NODE,
                payload: payload.freeze(),
            },
        );
    }
}
