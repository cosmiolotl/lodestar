use super::*;
use crate::world::{EPOCH, JOURNAL, READ, REVISION, Request, STORAGE, WRITE, response};

impl State {
    pub(super) fn world_request(&mut self, conn: ConnId, payload: Bytes, out: &mut Vec<Effect>) {
        if payload.first() == Some(&checkpoint::TAG) {
            self.checkpoint_ack(conn, payload, out);
            return;
        }
        if payload.first() != Some(&STORAGE) {
            self.world_checkpoint(conn, payload, out);
            return;
        }
        let Ok(request) = Request::decode(payload) else {
            out.push(Effect::Close(conn));
            return;
        };
        if self.saving_request(conn, &request, out) {
            return;
        }
        let allowed = match (self.peers.get(&conn), request.operation) {
            (Some(_), READ | JOURNAL | REVISION | EPOCH) => true,
            (Some(Peer::Node(node)), WRITE) => self.can_store_chunk(*node, &request),
            _ => false,
        };
        if allowed {
            out.push(Effect::WorldRequest {
                conn,
                request,
                committed: matches!(self.peers.get(&conn), Some(Peer::Proxy)),
            });
        } else {
            send(
                out,
                conn,
                &Message::DirectRelay {
                    from: NO_NODE,
                    payload: response(request.id, 3, b"not the authoritative worker"),
                },
            );
        }
    }

    pub(super) fn can_store_chunk(&self, node: NodeId, request: &Request) -> bool {
        if request.dimension == "cluster" && request.kind.starts_with("saved/") {
            return self.master_of(CLUSTER_SCOPE) == node;
        }
        if !matches!(request.kind.as_str(), "chunk" | "entities" | "poi") {
            return false;
        }
        let Some(dim) = self
            .dims
            .iter()
            .position(|dim| dim.name == request.dimension)
        else {
            return false;
        };
        let chunk = ChunkKey {
            dim: dim as u32,
            x: request.x,
            z: request.z,
        };
        let region = chunk.region();
        // Vanilla writes an unloaded chunk after withdrawing its subscription.
        // Its last owner may finish that save only until another owner takes over.
        self.regions
            .get(&region)
            .map(|region| region.owner)
            .or_else(|| self.history.get(&region).copied())
            == Some(node)
    }

    pub(super) fn persist_broadcast(
        &self,
        node: NodeId,
        scope: u32,
        payload: Bytes,
        out: &mut Vec<Effect>,
    ) {
        if self.master_of(scope) != node {
            return;
        }
        let name = self.storage_scope(scope);
        out.push(Effect::StoreGlobal {
            scope: name,
            payload,
        });
    }

    fn storage_scope(&self, scope: u32) -> String {
        if scope == CLUSTER_SCOPE {
            "cluster".into()
        } else {
            self.dims[scope as usize].name.clone()
        }
    }

    fn world_checkpoint(&self, conn: ConnId, payload: Bytes, out: &mut Vec<Effect>) {
        let Some(Peer::Node(node)) = self.peers.get(&conn) else {
            return;
        };
        let scope = match payload.first() {
            Some(10) if payload.len() >= 5 => u32::from_be_bytes(payload[1..5].try_into().unwrap()),
            Some(13) => CLUSTER_SCOPE,
            _ => return,
        };
        self.persist_broadcast(*node, scope, payload, out);
    }
}
