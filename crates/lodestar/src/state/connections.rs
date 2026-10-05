use super::*;

impl State {
    pub(super) fn hello(
        &mut self,
        conn: ConnId,
        msg: Message,
        now: Instant,
        out: &mut Vec<Effect>,
    ) {
        let reject = |out: &mut Vec<Effect>, reason: String| {
            warn!("rejecting connection {conn}: {reason}");
            send(out, conn, &Message::Rejected { reason });
            out.push(Effect::Close(conn));
        };
        let Message::Hello {
            protocol,
            token,
            name,
            role,
        } = msg
        else {
            return reject(out, "expected Hello".into());
        };
        if protocol != PROTOCOL_VERSION {
            return reject(
                out,
                format!(
                    "protocol mismatch: lodestar speaks {PROTOCOL_VERSION}, you speak {protocol}"
                ),
            );
        }
        if !token_matches(&self.policy.token, &token) {
            return reject(out, "invalid token".into());
        }
        match role {
            Role::Proxy => {
                info!("proxy {name} connected");
                self.peers.insert(conn, Peer::Proxy);
                send(out, conn, &Message::Welcome { node_id: NO_NODE });
            }
            Role::Node {
                game_addr,
                max_players,
            } => {
                if !self.requires_checkpoints() && name.starts_with("txn2:") {
                    return reject(
                        out,
                        "transactional workers require durable world storage".into(),
                    );
                }
                if self.requires_checkpoints() && !self.current_worker_epoch(&name) {
                    return reject(out, "worker must load from this coordinator epoch and support world asynchronous world transactions (txn2)".into());
                }
                if game_addr.is_empty() || max_players == 0 {
                    return reject(
                        out,
                        "a node needs a game address and a player capacity".into(),
                    );
                }
                let id = self.next_node;
                self.next_node += 1;
                info!(
                    "node {name} connected as #{id}, serving {max_players} players at {game_addr}"
                );

                send(out, conn, &Message::Welcome { node_id: id });
                let up = Message::NodeUp {
                    node: id,
                    name: name.clone(),
                }
                .to_frame();
                for (other_id, other) in &self.nodes {
                    send(
                        out,
                        conn,
                        &Message::NodeUp {
                            node: *other_id,
                            name: other.name.clone(),
                        },
                    );
                    out.push(Effect::Send(other.conn, up.clone()));
                }
                for (uuid, player) in &self.players {
                    let joined = Message::PlayerJoined {
                        node: player.node,
                        uuid: *uuid,
                        name: player.name.clone(),
                    };
                    send(out, conn, &joined);
                }

                // The node joins the clock at the next tick, not the one in progress.
                self.peers.insert(conn, Peer::Node(id));
                self.nodes.insert(
                    id,
                    Node {
                        conn,
                        name,
                        game_addr,
                        max_players,
                        players: HashSet::new(),
                        chunks: HashSet::new(),
                        dims: Vec::new(),
                        mspt: Duration::ZERO,
                        last_heartbeat: now,
                    },
                );
                self.introduce_master(id, CLUSTER_SCOPE, out);
            }
        }
    }

    /// Forgets a connection and everything that depended on it.
    pub fn disconnect(&mut self, conn: ConnId, out: &mut Vec<Effect>) {
        let id = match self.peers.remove(&conn) {
            Some(Peer::Node(id)) => id,
            Some(Peer::Storage(_)) => return,
            Some(Peer::Proxy) => {
                info!("proxy connection {conn} closed");
                for player in self.players.values_mut() {
                    if player.proxy == Some(conn) {
                        player.proxy = None;
                    }
                }
                self.forget_handoffs_of_proxy(conn, out);
                return;
            }
            None => return,
        };
        let node = self.nodes.remove(&id).expect("peer refers to a live node");
        info!("node {} (#{id}) disconnected", node.name);
        self.clock.pending.remove(&id);
        for uuid in node.players {
            self.players.remove(&uuid);
            self.broadcast(&Message::PlayerLeft { node: id, uuid }, out);
        }
        for dim in node.dims {
            self.dims[dim as usize].workers.retain(|w| *w != id);
        }

        // What it simulated is simulated by nobody until the next tick boundary
        // picks new owners, and what was on its way to it stays where it was.
        let mut touched = Vec::new();
        for (key, region) in &mut self.regions {
            if region.owner == id {
                region.owner = NO_NODE;
                touched.push(*key);
            }
            if region.incoming == id {
                region.incoming = NO_NODE;
                touched.push(*key);
            }
        }
        for handover in self.handovers.iter().filter(|h| h.from == id) {
            for key in &handover.regions {
                if let Some(region) = self.regions.get_mut(key) {
                    region.incoming = NO_NODE;
                    touched.push(*key);
                }
            }
        }
        self.handovers.retain(|h| h.from != id && h.to != id);
        self.replan = true;

        for chunk in node.chunks {
            self.remove_subscriber(id, chunk, out);
        }
        for key in touched {
            self.refresh_region_demand(key, out);
        }
        self.forget_custody_of(id, out);
        self.forget_player_data_of(id, out);
        self.forget_handoffs_of_node(id, out);
        self.reservations.retain(|_, r| r.node != id);
        self.broadcast(&Message::NodeDown { node: id }, out);
    }
}
