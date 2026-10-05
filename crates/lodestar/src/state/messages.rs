use super::*;

impl State {
    pub fn handle(&mut self, conn: ConnId, msg: Message, now: Instant, out: &mut Vec<Effect>) {
        let Some(&peer) = self.peers.get(&conn) else {
            return self.hello(conn, msg, now, out);
        };
        if !self.checkpoint_traffic(conn, &msg) {
            return;
        }
        match (peer, msg) {
            (Peer::Node(id), Message::Heartbeat { mspt_micros, .. }) => {
                let node = self.node_mut(id);
                node.mspt = Duration::from_micros(mspt_micros.into());
                node.last_heartbeat = now;
            }
            (Peer::Node(id), Message::TickDone { tick }) => {
                // An answer to a tick that has already been given up on is ignored.
                if tick == self.clock.tick && self.clock.pending.remove(&id) {
                    self.note_tick_done(id, now);
                }
            }
            (Peer::Node(id), Message::ResolveDimension { name }) => {
                self.join_dimension(id, name, out);
            }
            (Peer::Node(id), Message::PlayerJoin { uuid, name }) => {
                self.player_join(id, uuid, name, out);
            }
            (Peer::Node(id), Message::PlayerLeave { uuid }) => {
                // Ignore a late leave from a node the player has already moved away from.
                if self.players.get(&uuid).is_some_and(|p| p.node == id) {
                    self.player_leave(uuid, out);
                }
            }
            (Peer::Node(id), Message::PlayerAt { uuid, chunk }) => {
                if let Some(player) = self.players.get_mut(&uuid)
                    && player.node == id
                {
                    if player.at.map(|at| at.region()) != Some(chunk.region()) {
                        self.replan = true;
                    }
                    player.at = Some(chunk);
                }
            }
            (Peer::Node(id), Message::Subscribe { chunk }) => {
                if self.node_mut(id).chunks.insert(chunk) {
                    self.change_chunk(chunk, out, |c| c.subs.push(id));
                }
            }
            (Peer::Node(id), Message::Unsubscribe { chunk }) => {
                if self.node_mut(id).chunks.remove(&chunk) {
                    self.remove_subscriber(id, chunk, out);
                }
            }
            (Peer::Node(id), Message::SetTicking { chunk, ticking }) => {
                // This can race with the sender's own unsubscribe; drop it then.
                if self.nodes[&id].chunks.contains(&chunk) {
                    self.change_chunk(chunk, out, |c| {
                        c.ticking.retain(|n| *n != id);
                        if ticking {
                            c.ticking.push(id);
                        }
                    });
                }
            }
            (Peer::Node(id), Message::Synced { chunk }) => {
                // Like above, and it asks nothing new of anyone: it only counts
                // towards handovers, which are looked at between ticks.
                if let Some(entry) = self.chunks.get_mut(&chunk)
                    && entry.subs.contains(&id)
                    && !entry.synced.contains(&id)
                {
                    entry.synced.push(id);
                }
            }
            (Peer::Node(id), Message::Publish { chunk, payload }) => {
                // Like above, a publish can race with the sender's own unsubscribe.
                let Some(entry) = self.chunks.get(&chunk).filter(|c| c.subs.contains(&id)) else {
                    return;
                };
                if entry.subs.len() == 1 {
                    return;
                }
                let frame = Message::Relay {
                    from: id,
                    chunk,
                    payload,
                }
                .to_frame();
                for sub in entry.subs.iter().filter(|s| **s != id) {
                    out.push(Effect::Send(self.nodes[sub].conn, frame.clone()));
                }
            }
            (
                Peer::Node(id),
                Message::Claim {
                    claim,
                    chunk,
                    object,
                },
            ) => self.claim(id, claim, chunk, object, out),
            (
                Peer::Node(id),
                Message::ClaimAnswer {
                    claim,
                    claimant,
                    granted,
                    payload,
                },
            ) => self.claim_answer(id, claim, claimant, granted, payload, out),
            (
                Peer::Node(id),
                Message::Release {
                    chunk,
                    object,
                    payload,
                },
            ) => self.release(id, chunk, object, payload, out),
            (Peer::Node(id), Message::PlayerDataRequest { uuid }) => {
                self.player_data_request(id, uuid, out)
            }
            (Peer::Node(id), Message::PlayerDataSave { uuid, data }) => {
                self.player_data_save(id, uuid, data, out)
            }
            (Peer::Node(id), Message::PlayerDataRelease { uuid }) => {
                self.player_data_release(id, uuid, out)
            }
            (
                _,
                Message::Direct {
                    to: NO_NODE,
                    payload,
                },
            ) => self.world_request(conn, payload, out),
            (Peer::Node(id), Message::Direct { to, payload }) => {
                if let Some(target) = self.nodes.get(&to) {
                    send(
                        out,
                        target.conn,
                        &Message::DirectRelay { from: id, payload },
                    );
                }
            }
            (Peer::Node(id), Message::Broadcast { scope, payload }) => {
                self.broadcast_state(id, scope, payload, out)
            }
            (Peer::Node(id), Message::IdBlockRequest) => self.id_block_request(id, out),
            (Peer::Node(id), Message::CounterBlockRequest { counter, floor }) => {
                self.counter_block_request(id, counter, floor, out)
            }
            (Peer::Node(id), Message::TickInterval { nanos }) => {
                self.tick_interval_request(id, nanos)
            }
            (Peer::Node(id), Message::Announce { payload }) => {
                let frame = Message::AnnounceRelay { from: id, payload }.to_frame();
                for (other, node) in &self.nodes {
                    if *other != id {
                        out.push(Effect::Send(node.conn, frame.clone()));
                    }
                }
            }
            (Peer::Node(id), Message::HandoffRequest { uuid, to }) => {
                self.handoff_request(id, uuid, to, out)
            }
            (Peer::Node(id), Message::HandoffReady { uuid }) => self.handoff_ready(id, uuid, out),
            (Peer::Node(id), Message::HandoffState { uuid, data, state }) => {
                self.handoff_state(id, uuid, data, state, out)
            }
            (Peer::Node(id), Message::HandoffAbort { uuid }) => self.handoff_abort(id, uuid, out),
            (Peer::Node(id), Message::HandoffDone { uuid }) => self.handoff_done(id, uuid, out),
            (Peer::Proxy, Message::HandoffFailed { uuid }) => self.handoff_failed(conn, uuid, out),
            (Peer::Proxy, Message::HandoffFrozen { uuid, frames }) => {
                self.handoff_frozen(conn, uuid, frames, out)
            }
            (
                Peer::Proxy,
                Message::PlaceRequest {
                    request_id,
                    uuid,
                    name,
                },
            ) => {
                let outcome = self.place(uuid, conn, now);
                match &outcome {
                    PlacementOutcome::Node { node, .. } => {
                        info!("placing {name} on {}", self.nodes[node].name)
                    }
                    PlacementOutcome::Denied { reason } => info!("refusing {name}: {reason}"),
                }
                send(
                    out,
                    conn,
                    &Message::Placement {
                        request_id,
                        outcome,
                    },
                );
            }
            (Peer::Proxy, Message::StatusRequest { request_id }) => {
                let capacity = self
                    .nodes
                    .values()
                    .filter(|n| self.is_healthy(n, now))
                    .map(|n| n.max_players)
                    .sum();
                let online = self.players.len() as u32;
                send(
                    out,
                    conn,
                    &Message::Status {
                        request_id,
                        online,
                        capacity,
                    },
                );
            }
            (_, other) => {
                warn!("connection {conn} sent an unexpected message: {other:?}");
                out.push(Effect::Close(conn));
            }
        }
    }
}
