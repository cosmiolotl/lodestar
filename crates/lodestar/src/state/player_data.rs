//! Players' saved data: inventory, ender chest, position, health and the rest.
//!
//! lodestar keeps the one copy of each player's data that counts, and lends it
//! to one node at a time: the node the player joins asks for it, and gives it
//! back, saved, when the player leaves. A node asking for data another node
//! still has waits until it is given back. So a player's items are always in
//! one place, whichever node the player was last on and whichever they join.

use super::*;

impl State {
    /// Adds data read back from storage at startup.
    pub fn load_player_data(&mut self, uuid: u128, data: Bytes) {
        self.player_data.insert(uuid, data);
    }

    pub(super) fn player_data_request(&mut self, node: NodeId, uuid: u128, out: &mut Vec<Effect>) {
        match self.data_custody.get(&uuid) {
            Some(&holder) if holder != node => {
                if !self.data_waiting.contains(&(node, uuid)) {
                    info!(
                        "node #{node} waits for node #{holder} to give back the data of {}",
                        self.player_name(uuid)
                    );
                    self.data_waiting.push((node, uuid));
                }
            }
            _ => self.lend_player_data(node, uuid, out),
        }
    }

    fn lend_player_data(&mut self, node: NodeId, uuid: u128, out: &mut Vec<Effect>) {
        self.data_custody.insert(uuid, node);
        let data = Message::PlayerData {
            uuid,
            data: self.player_data.get(&uuid).cloned(),
        };
        send(out, self.nodes[&node].conn, &data);
    }

    pub(super) fn player_data_save(
        &mut self,
        node: NodeId,
        uuid: u128,
        data: Bytes,
        out: &mut Vec<Effect>,
    ) {
        // A node that has given the data back, or never had it, has an old copy.
        if self.data_custody.get(&uuid) != Some(&node) {
            warn!(
                "node #{node} tried to save the data of {} without having it",
                self.player_name(uuid)
            );
            return;
        }
        self.player_data.insert(uuid, data.clone());
        out.push(Effect::StorePlayerData { uuid, data });
    }

    pub(super) fn player_data_release(&mut self, node: NodeId, uuid: u128, out: &mut Vec<Effect>) {
        if self.data_custody.get(&uuid) == Some(&node) {
            self.data_custody.remove(&uuid);
            self.lend_to_next(uuid, out);
        }
    }

    pub(super) fn lend_to_next(&mut self, uuid: u128, out: &mut Vec<Effect>) {
        if let Some(index) = self.data_waiting.iter().position(|(_, u)| *u == uuid) {
            let (next, _) = self.data_waiting.remove(index);
            self.lend_player_data(next, uuid, out);
        }
    }

    /// A node that left gives back whatever data it had, as last saved.
    pub(super) fn forget_player_data_of(&mut self, node: NodeId, out: &mut Vec<Effect>) {
        self.data_waiting.retain(|(n, _)| *n != node);
        let mut held: Vec<u128> = self
            .data_custody
            .iter()
            .filter(|(_, holder)| **holder == node)
            .map(|(uuid, _)| *uuid)
            .collect();
        held.sort_unstable();
        for uuid in held {
            self.data_custody.remove(&uuid);
            self.lend_to_next(uuid, out);
        }
    }

    pub(super) fn player_name(&self, uuid: u128) -> String {
        self.players
            .get(&uuid)
            .map_or_else(|| format!("{uuid:032x}"), |p| p.name.clone())
    }
}
