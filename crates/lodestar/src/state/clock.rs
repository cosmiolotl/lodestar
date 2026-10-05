use super::*;

impl State {
    /// Starts the next tick if it is due, and drops nodes that are holding up
    /// the current one for too long. Returns when to call again.
    pub fn poll_clock(&mut self, now: Instant, out: &mut Vec<Effect>) -> Option<Instant> {
        self.poll_upload(now, out);
        if self.nodes.is_empty() {
            return None;
        }
        if let Some(started) = self.clock.started
            && !self.clock.pending.is_empty()
        {
            let deadline = started + self.policy.tick_timeout;
            if now < deadline {
                return Some(deadline);
            }
            for id in &self.clock.pending {
                let node = &self.nodes[id];
                warn!(
                    "node {} (#{id}) has not finished tick {} in {:?}, dropping it",
                    node.name, self.clock.tick, self.policy.tick_timeout
                );
                out.push(Effect::Close(node.conn));
            }
            // The closes empty the pending set; look again once they have been carried out.
            return Some(now);
        }

        if self.checkpoint_step(now, out) {
            return Some(self.clock.started.unwrap_or(now) + self.policy.tick_timeout);
        }
        let due = self.clock.due.unwrap_or(now);
        if now < due {
            return Some(due);
        }
        // A tick that is late starts right away. A little lateness is made up
        // by keeping to the schedule, but a tick that is more than one interval
        // late restarts it: lost time is not made up with a burst of ticks.
        let interval = self.tick_interval;
        self.clock.due = Some(if now - due > interval {
            now + interval
        } else {
            due + interval
        });
        self.note_tick_start(now);
        self.clock.tick += 1;
        self.clock.started = Some(now);
        self.clock.pending = self.nodes.keys().copied().collect();
        self.checkpoint_started_tick();
        // Every node has finished the last tick, and everything it published
        // during it has been relayed. Ownership changes here, so that every
        // node sees it at the same point: after the old owner's last changes
        // and before the new owner's first.
        self.plan(out);
        self.plan_masters(out);
        self.expire_handoffs(out);
        self.plan_handoffs(now, out);
        self.broadcast(
            &Message::Tick {
                tick: self.clock.tick,
            },
            out,
        );
        Some(now + self.policy.tick_timeout)
    }

    pub(super) fn note_tick_done(&mut self, node: NodeId, now: Instant) {
        let Some(started) = self.clock.started else {
            return;
        };
        let last = self.clock.pending.is_empty();
        let entry = self.clock.stats.done.entry(node).or_default();
        entry.0 += now - started;
        if last {
            entry.1 += 1;
            self.clock.stats.last_done = Some(now);
        }
    }

    /// Every 100 ticks, says at debug level how fast they went and which node
    /// held them up.
    fn note_tick_start(&mut self, now: Instant) {
        let stats = &mut self.clock.stats;
        if let Some(done) = stats.last_done.take() {
            stats.between += now - done;
        }
        let Some(since) = stats.since else {
            stats.since = Some(now);
            stats.first_tick = self.clock.tick;
            return;
        };
        let ticks = self.clock.tick - stats.first_tick;
        if ticks < 100 {
            return;
        }
        let per_tick = |d: Duration| d / ticks as u32;
        let nodes: Vec<String> = stats
            .done
            .iter()
            .map(|(id, (total, last))| {
                let name = self.nodes.get(id).map_or("gone", |n| n.name.as_str());
                format!("{name} {:?} (last {last})", per_tick(*total))
            })
            .collect();
        debug!(
            "ticks {}-{}: {:.1} per second; nodes finished after {}; next tick started {:?} later",
            stats.first_tick,
            self.clock.tick,
            ticks as f64 / (now - since).as_secs_f64(),
            nodes.join(", "),
            per_tick(stats.between),
        );
        *stats = TickStats {
            since: Some(now),
            first_tick: self.clock.tick,
            ..TickStats::default()
        };
    }

    pub(super) fn node_mut(&mut self, id: NodeId) -> &mut Node {
        self.nodes.get_mut(&id).expect("peer refers to a live node")
    }

    pub(super) fn broadcast(&self, msg: &Message, out: &mut Vec<Effect>) {
        let frame = msg.to_frame();
        for node in self.nodes.values() {
            out.push(Effect::Send(node.conn, frame.clone()));
        }
    }

    pub(super) fn tell_workers(&self, dim: u32, msg: &Message, out: &mut Vec<Effect>) {
        let Some(dimension) = self.dims.get(dim as usize) else {
            return;
        };
        let frame = msg.to_frame();
        for worker in &dimension.workers {
            out.push(Effect::Send(self.nodes[worker].conn, frame.clone()));
        }
    }
}
