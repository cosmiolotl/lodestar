//! Save sockets inherit a live worker's authority without joining the tick clock.
use super::*;
use crate::world::{BIND, Request, SNAPSHOT, response};

#[derive(Default)]
pub(super) struct SaveAuthority {
    regions: HashMap<RegionKey, NodeId>,
    players: HashMap<u128, NodeId>,
    master: NodeId,
}

impl State {
    pub(super) fn capture_save_authority(&self) -> SaveAuthority {
        let mut regions = self.history.clone();
        regions.extend(self.regions.iter().map(|(key, value)| (*key, value.owner)));
        SaveAuthority {
            regions,
            players: self.data_custody.clone(),
            master: self.master_of(CLUSTER_SCOPE),
        }
    }

    pub(super) fn saving_request(
        &mut self,
        conn: ConnId,
        request: &Request,
        out: &mut Vec<Effect>,
    ) -> bool {
        if request.operation == BIND {
            let valid =
                request.data.len() == 12 && matches!(self.peers.get(&conn), Some(Peer::Proxy));
            if !valid {
                out.push(Effect::Close(conn));
                return true;
            }
            let node = u32::from_be_bytes(request.data[..4].try_into().unwrap());
            let epoch = u64::from_be_bytes(request.data[4..].try_into().unwrap());
            if !self.valid_save_binding(node, epoch) {
                out.push(Effect::Close(conn));
                return true;
            }
            self.peers.insert(conn, Peer::Storage(node));
            send(
                out,
                conn,
                &Message::DirectRelay {
                    from: 0,
                    payload: response(request.id, 0, &[]),
                },
            );
            return true;
        }
        if request.operation != SNAPSHOT {
            return false;
        }
        let Some(Peer::Storage(node)) = self.peers.get(&conn).copied() else {
            out.push(Effect::Close(conn));
            return true;
        };
        if request.data.len() < 20 {
            out.push(Effect::Close(conn));
            return true;
        }
        let epoch = u64::from_be_bytes(request.data[..8].try_into().unwrap());
        let revision = u64::from_be_bytes(request.data[8..16].try_into().unwrap());
        let round = u32::from_be_bytes(request.data[16..20].try_into().unwrap());
        if !self.accepts_snapshot(node, epoch, revision, round)
            || !self.snapshot_authority(node, request)
        {
            out.push(Effect::Close(conn));
            return true;
        }
        let mut request = request.clone();
        request.data = request.data.slice(20..);
        out.push(Effect::SnapshotRecord { conn, request });
        true
    }

    fn snapshot_authority(&self, node: NodeId, request: &Request) -> bool {
        let authority = &self.checkpoint.authority;
        if request.dimension == "players" {
            return u128::from_str_radix(&request.kind, 16)
                .ok()
                .is_some_and(|uuid| authority.players.get(&uuid) == Some(&node));
        }
        if request.dimension == "cluster" && request.kind.starts_with("saved/") {
            return authority.master == node;
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
        let region = ChunkKey {
            dim: dim as u32,
            x: request.x,
            z: request.z,
        }
        .region();
        authority.regions.get(&region) == Some(&node)
    }
}
