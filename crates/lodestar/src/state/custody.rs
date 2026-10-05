//! Custody: which node is the authority for an object that is not its
//! region's owner.
//!
//! An object (an entity or a block entity, named by bytes lodestar does not
//! interpret) is normally simulated by the owner of the region it is in. A node
//! whose player wants to use the object asks for custody of it; the owner hands
//! over the object's state, and the claimant becomes its authority until it
//! hands it back. lodestar grants custody of an object to one node at a time,
//! which is what keeps two nodes from both changing it, and so from both
//! handing out what is in it.

use super::*;

/// An object, by dimension and the mod's name for it.
pub(super) type ObjectKey = (u32, Bytes);

pub(super) struct Held {
    pub(super) holder: NodeId,
    /// The chunk the object was in when custody was granted.
    pub(super) chunk: ChunkKey,
}

pub(super) struct PendingClaim {
    /// The owner that was asked to hand the object over.
    pub(super) owner: NodeId,
    pub(super) chunk: ChunkKey,
    pub(super) object: Bytes,
}

impl State {
    pub(super) fn claim(
        &mut self,
        claimant: NodeId,
        claim: u32,
        chunk: ChunkKey,
        object: Bytes,
        out: &mut Vec<Effect>,
    ) {
        let key: ObjectKey = (chunk.dim, object.clone());
        let busy = self.custody.contains_key(&key)
            || self
                .claims
                .values()
                .any(|c| c.chunk.dim == chunk.dim && c.object == object);
        let owner = self
            .regions
            .get(&chunk.region())
            .map_or(NO_NODE, |r| r.owner);
        // A claimant that owns the region is already the authority; its view
        // of who owns what is out of date, and it asks again once it is not.
        if busy || owner == NO_NODE || owner == claimant {
            let denied = Message::ClaimResult {
                claim,
                granted: false,
                payload: Bytes::new(),
            };
            send(out, self.nodes[&claimant].conn, &denied);
            return;
        }
        self.claims.insert(
            (claimant, claim),
            PendingClaim {
                owner,
                chunk,
                object: object.clone(),
            },
        );
        let request = Message::ClaimRequest {
            claim,
            claimant,
            chunk,
            object,
        };
        send(out, self.nodes[&owner].conn, &request);
    }

    pub(super) fn claim_answer(
        &mut self,
        owner: NodeId,
        claim: u32,
        claimant: NodeId,
        granted: bool,
        payload: Bytes,
        out: &mut Vec<Effect>,
    ) {
        if self
            .claims
            .get(&(claimant, claim))
            .is_none_or(|c| c.owner != owner)
        {
            return;
        }
        let pending = self
            .claims
            .remove(&(claimant, claim))
            .expect("just looked up");
        // A claimant that has left takes nothing; the owner, which only wrote
        // the object down, carries on as its authority.
        let Some(conn) = self.nodes.get(&claimant).map(|n| n.conn) else {
            return;
        };
        if granted {
            let dim = pending.chunk.dim;
            self.custody.insert(
                (dim, pending.object.clone()),
                Held {
                    holder: claimant,
                    chunk: pending.chunk,
                },
            );
            // Before the result, so that the claimant is the authority by the
            // time it applies the state it was handed.
            let custody = Message::Custody {
                dim,
                object: pending.object,
                holder: claimant,
            };
            self.tell_workers(dim, &custody, out);
        }
        send(
            out,
            conn,
            &Message::ClaimResult {
                claim,
                granted,
                payload,
            },
        );
    }

    pub(super) fn release(
        &mut self,
        holder: NodeId,
        chunk: ChunkKey,
        object: Bytes,
        payload: Bytes,
        out: &mut Vec<Effect>,
    ) {
        let key: ObjectKey = (chunk.dim, object.clone());
        if self.custody.get(&key).map(|h| h.holder) != Some(holder) {
            return;
        }
        self.custody.remove(&key);
        self.hand_back(chunk, object, payload, out);
    }

    /// Tells everyone an object is back with its region's owner, and gives the
    /// owner the object's state.
    fn hand_back(&mut self, chunk: ChunkKey, object: Bytes, payload: Bytes, out: &mut Vec<Effect>) {
        let custody = Message::Custody {
            dim: chunk.dim,
            object: object.clone(),
            holder: NO_NODE,
        };
        self.tell_workers(chunk.dim, &custody, out);
        let owner = self
            .regions
            .get(&chunk.region())
            .map_or(NO_NODE, |r| r.owner);
        // With no owner yet, whoever is named next simulates its own copy.
        if let Some(node) = self.nodes.get(&owner) {
            let released = Message::Released {
                chunk,
                object,
                payload,
            };
            send(out, node.conn, &released);
        }
    }

    /// Settles the claims and custody of a node that has left. What it held
    /// goes back to the owners, without the changes since it last published
    /// them.
    pub(super) fn forget_custody_of(&mut self, node: NodeId, out: &mut Vec<Effect>) {
        let mut orphaned: Vec<(NodeId, u32)> = Vec::new();
        self.claims.retain(|&(claimant, claim), pending| {
            if claimant == node {
                return false;
            }
            if pending.owner == node {
                orphaned.push((claimant, claim));
                return false;
            }
            true
        });
        orphaned.sort_unstable();
        for (claimant, claim) in orphaned {
            if let Some(n) = self.nodes.get(&claimant) {
                let denied = Message::ClaimResult {
                    claim,
                    granted: false,
                    payload: Bytes::new(),
                };
                send(out, n.conn, &denied);
            }
        }

        let mut held: Vec<(ObjectKey, ChunkKey)> = self
            .custody
            .iter()
            .filter(|(_, h)| h.holder == node)
            .map(|(key, h)| (key.clone(), h.chunk))
            .collect();
        held.sort_unstable_by(|a, b| a.0.cmp(&b.0));
        for ((_, object), chunk) in held {
            self.custody.remove(&(chunk.dim, object.clone()));
            self.hand_back(chunk, object, Bytes::new(), out);
        }
    }

    /// What a node that has just become a worker of a dimension must know
    /// about who has custody of what in it.
    pub(super) fn custody_in(&self, dim: u32) -> Vec<Message> {
        let mut held: Vec<Message> = self
            .custody
            .iter()
            .filter(|((d, _), _)| *d == dim)
            .map(|((d, object), h)| Message::Custody {
                dim: *d,
                object: object.clone(),
                holder: h.holder,
            })
            .collect();
        held.sort_unstable_by_key(|m| match m {
            Message::Custody { object, .. } => object.clone(),
            _ => Bytes::new(),
        });
        held
    }
}
