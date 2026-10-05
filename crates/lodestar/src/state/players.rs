use super::*;

impl State {
    pub(super) fn player_join(
        &mut self,
        id: NodeId,
        uuid: u128,
        name: String,
        out: &mut Vec<Effect>,
    ) {
        let proxy = self
            .reservations
            .remove(&uuid)
            .filter(|r| r.node == id)
            .map(|r| r.proxy);
        if let Some(previous) = self.players.get(&uuid) {
            let from = previous.node;
            warn!("{name} joined node #{id} while still registered on node #{from}");
            self.player_leave(uuid, out);
        }
        self.node_mut(id).players.insert(uuid);
        self.broadcast(
            &Message::PlayerJoined {
                node: id,
                uuid,
                name: name.clone(),
            },
            out,
        );
        self.players.insert(
            uuid,
            Player {
                node: id,
                name,
                at: None,
                proxy,
                movable_from: self.clock.tick + handoff::SETTLE_TICKS,
            },
        );
    }

    pub(super) fn player_leave(&mut self, uuid: u128, out: &mut Vec<Effect>) {
        self.forget_handoff_of(uuid, out);
        let Some(player) = self.players.remove(&uuid) else {
            return;
        };
        if let Some(node) = self.nodes.get_mut(&player.node) {
            node.players.remove(&uuid);
        }
        if player.at.is_some() {
            self.replan = true;
        }
        self.broadcast(
            &Message::PlayerLeft {
                node: player.node,
                uuid,
            },
            out,
        );
    }

    pub(super) fn is_healthy(&self, node: &Node, now: Instant) -> bool {
        now.duration_since(node.last_heartbeat) <= self.policy.heartbeat_timeout
    }

    /// Picks a node for a joining player and reserves a slot on it: the
    /// emptiest one, passing over nodes that are silent or ticking slowly.
    /// Spreading players evenly spreads the per-connection work, which is what
    /// grows fastest in a crowd.
    pub(super) fn place(&mut self, uuid: u128, proxy: ConnId, now: Instant) -> PlacementOutcome {
        if self.players.contains_key(&uuid) {
            return PlacementOutcome::Denied {
                reason: "You are already connected.".into(),
            };
        }
        self.reservations.retain(|_, r| r.expires > now);
        self.reservations.remove(&uuid);

        let mut load: HashMap<NodeId, u32> = self
            .nodes
            .iter()
            .map(|(id, n)| (*id, n.players.len() as u32))
            .collect();
        for reservation in self.reservations.values() {
            *load.entry(reservation.node).or_default() += 1;
        }
        let fill = |id: &NodeId| load[id] as f32 / self.nodes[id].max_players as f32;
        let is_slow = |id: &NodeId| self.nodes[id].mspt > self.policy.mspt_limit;

        let mut candidates: Vec<NodeId> = self
            .nodes
            .iter()
            .filter(|(id, n)| self.is_healthy(n, now) && load[*id] < n.max_players)
            .map(|(id, _)| *id)
            .collect();
        // Break ties the same way every time.
        candidates.sort_unstable();

        let choice = candidates.iter().copied().min_by(|a, b| {
            (is_slow(a), fill(a))
                .partial_cmp(&(is_slow(b), fill(b)))
                .unwrap_or(std::cmp::Ordering::Equal)
        });

        match choice {
            Some(node) => {
                let expires = now + self.policy.reservation_ttl;
                self.reservations.insert(
                    uuid,
                    Reservation {
                        node,
                        proxy,
                        expires,
                    },
                );
                PlacementOutcome::Node {
                    node,
                    addr: self.nodes[&node].game_addr.clone(),
                }
            }
            None if self.nodes.is_empty() => PlacementOutcome::Denied {
                reason: "No servers are online right now.".into(),
            },
            None => PlacementOutcome::Denied {
                reason: "The server is full.".into(),
            },
        }
    }
}
