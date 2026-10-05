//! Masters: the one node that keeps the state of each scope.
//!
//! A server keeps some of its state once for all of its dimensions (the time of
//! day, the weather, the game rules) and some once per dimension (the world
//! border). In a cluster every node has a copy of all of it, so one node of each
//! scope, its master, keeps the truth and broadcasts it, and the others adopt
//! what it broadcasts. The scopes are the cluster ([`CLUSTER_SCOPE`]), whose
//! nodes are all the nodes, and each dimension, whose nodes are its workers.
//!
//! A master keeps its scope for as long as it is connected and of the scope, so
//! that the truth does not move between nodes needlessly. When it leaves, the
//! node of the scope that joined it first takes over. Like region ownership,
//! masters only change between two ticks, so that every node sees the change at
//! the same point.

use super::*;

impl State {
    /// The nodes of a scope, in joining order.
    fn scope_nodes(&self, scope: u32) -> Vec<NodeId> {
        if scope == CLUSTER_SCOPE {
            // Node ids are handed out in joining order.
            let mut nodes: Vec<NodeId> = self.nodes.keys().copied().collect();
            nodes.sort_unstable();
            nodes
        } else {
            self.dims
                .get(scope as usize)
                .map_or_else(Vec::new, |d| d.workers.clone())
        }
    }

    fn scope_name(&self, scope: u32) -> &str {
        if scope == CLUSTER_SCOPE {
            "the cluster"
        } else {
            &self.dims[scope as usize].name
        }
    }

    /// The master of a scope, or [`NO_NODE`] if it has none, or it has left
    /// and the next tick boundary has yet to pick another.
    pub(super) fn master_of(&self, scope: u32) -> NodeId {
        let Some(&master) = self.masters.get(&scope) else {
            return NO_NODE;
        };
        let present = if scope == CLUSTER_SCOPE {
            self.nodes.contains_key(&master)
        } else {
            self.dims
                .get(scope as usize)
                .is_some_and(|dimension| dimension.workers.contains(&master))
        };
        if present { master } else { NO_NODE }
    }

    /// Runs between two ticks: gives each scope that has no master, or whose
    /// master has left it, a new one, and tells the scope's nodes.
    pub(super) fn plan_masters(&mut self, out: &mut Vec<Effect>) {
        let scopes: Vec<u32> = std::iter::once(CLUSTER_SCOPE)
            .chain(0..self.dims.len() as u32)
            .collect();
        for scope in scopes {
            if self.master_of(scope) != NO_NODE {
                continue;
            }
            let nodes = self.scope_nodes(scope);
            let Some(&master) = nodes.first() else {
                self.masters.remove(&scope);
                continue;
            };
            info!(
                "node #{master} keeps the state of {}",
                self.scope_name(scope)
            );
            self.masters.insert(scope, master);
            let frame = Message::Master { scope, master }.to_frame();
            for node in nodes {
                out.push(Effect::Send(self.nodes[&node].conn, frame.clone()));
            }
        }
    }

    /// Tells a node that has just joined a scope who its master is, if it has one.
    pub(super) fn introduce_master(&self, node: NodeId, scope: u32, out: &mut Vec<Effect>) {
        let master = self.master_of(scope);
        if master != NO_NODE {
            send(
                out,
                self.nodes[&node].conn,
                &Message::Master { scope, master },
            );
        }
    }

    /// Relays what a scope's master broadcasts to the rest of the scope.
    pub(super) fn broadcast_state(
        &self,
        from: NodeId,
        scope: u32,
        payload: Bytes,
        out: &mut Vec<Effect>,
    ) {
        // A node that has just lost its scope, or never had it, is not heard:
        // the scope has one source of truth.
        if self.master_of(scope) != from {
            return;
        }
        self.persist_broadcast(from, scope, payload.clone(), out);
        let frame = Message::BroadcastRelay {
            from,
            scope,
            payload,
        }
        .to_frame();
        for node in self.scope_nodes(scope) {
            if node != from {
                out.push(Effect::Send(self.nodes[&node].conn, frame.clone()));
            }
        }
    }
}
