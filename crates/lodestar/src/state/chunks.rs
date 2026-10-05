use super::*;

impl State {
    pub(super) fn join_dimension(&mut self, id: NodeId, name: String, out: &mut Vec<Effect>) {
        let dim = match self.dims.iter().position(|d| d.name == name) {
            Some(dim) => dim,
            None => {
                self.dims.push(Dimension {
                    name: name.clone(),
                    workers: Vec::new(),
                });
                self.dims.len() - 1
            }
        } as u32;
        let conn = self.nodes[&id].conn;
        send(out, conn, &Message::DimensionResolved { name, id: dim });

        if self.dims[dim as usize].workers.contains(&id) {
            return;
        }
        self.dims[dim as usize].workers.push(id);
        self.node_mut(id).dims.push(dim);
        self.replan = true;
        let mut owned: Vec<(RegionKey, NodeId)> = self
            .regions
            .iter()
            .filter(|(key, region)| key.dim == dim && region.announced != NO_NODE)
            .map(|(key, region)| (*key, region.announced))
            .collect();
        owned.sort_unstable();
        for (region, owner) in owned {
            send(out, conn, &Message::RegionOwner { region, owner });
        }
        for custody in self.custody_in(dim) {
            send(out, conn, &custody);
        }
        self.introduce_master(id, dim, out);
    }

    /// Applies `change` to a chunk's entry, creating it if need be, keeps its
    /// region in step, and tells the nodes that hold the chunk for the others
    /// if that changes what is demanded of them.
    pub(super) fn change_chunk(
        &mut self,
        key: ChunkKey,
        out: &mut Vec<Effect>,
        change: impl FnOnce(&mut Chunk),
    ) {
        let entry = self.chunks.entry(key).or_default();
        let was_active = !entry.subs.is_empty();
        change(entry);
        let active = !entry.subs.is_empty();
        if active && !was_active {
            match self.regions.get_mut(&key.region()) {
                Some(region) => {
                    region.chunks.insert(key);
                }
                None => {
                    self.regions.insert(key.region(), Region::new(key));
                    self.replan = true;
                }
            }
        }
        self.refresh_demand(key, out);
        if !active {
            self.chunks.remove(&key);
            if was_active {
                self.leave_region(key);
            }
        }
    }

    /// Removes `node` from a chunk's subscribers. The caller updates `Node::chunks`.
    pub(super) fn remove_subscriber(&mut self, node: NodeId, key: ChunkKey, out: &mut Vec<Effect>) {
        self.change_chunk(key, out, |c| {
            c.subs.retain(|n| *n != node);
            c.ticking.retain(|n| *n != node);
            c.synced.retain(|n| *n != node);
        });
    }

    /// A chunk nobody has loaded any more leaves its region, and the region
    /// goes out of use with its last chunk.
    pub(super) fn leave_region(&mut self, chunk: ChunkKey) {
        let key = chunk.region();
        let Some(region) = self.regions.get_mut(&key) else {
            return;
        };
        region.chunks.remove(&chunk);
        if region.chunks.is_empty() {
            let region = self.regions.remove(&key).expect("just looked it up");
            if region.owner != NO_NODE {
                self.history.insert(key, region.owner);
            }
            if region.announced != NO_NODE {
                self.retired.push(key);
            }
            self.replan = true;
        }
    }

    /// Brings what the holders of a chunk have been told into line with what
    /// the other nodes need of it. A chunk's holders are the owner of its
    /// region and the node the region is being handed to, if any.
    pub(super) fn refresh_demand(&mut self, key: ChunkKey, out: &mut Vec<Effect>) {
        let holders = self
            .regions
            .get(&key.region())
            .map_or([NO_NODE; 2], |r| [r.owner, r.incoming]);
        let Some(chunk) = self.chunks.get_mut(&key) else {
            return;
        };
        let mut changes = Vec::new();
        chunk.sent.retain(|&(node, _)| {
            let still_holds = holders.contains(&node);
            if !still_holds {
                changes.push((node, Demand::None));
            }
            still_holds
        });
        for holder in holders {
            if holder == NO_NODE {
                continue;
            }
            let demand = chunk.demand(holder);
            let index = chunk.sent.iter().position(|(n, _)| *n == holder);
            if demand == index.map_or(Demand::None, |i| chunk.sent[i].1) {
                continue;
            }
            match (index, demand) {
                (Some(i), Demand::None) => {
                    chunk.sent.swap_remove(i);
                }
                (Some(i), _) => chunk.sent[i].1 = demand,
                (None, _) => chunk.sent.push((holder, demand)),
            }
            changes.push((holder, demand));
        }
        for (node, demand) in changes {
            if let Some(node) = self.nodes.get(&node) {
                send(out, node.conn, &Message::ChunkDemand { chunk: key, demand });
            }
        }
    }

    pub(super) fn refresh_region_demand(&mut self, key: RegionKey, out: &mut Vec<Effect>) {
        let Some(region) = self.regions.get(&key) else {
            return;
        };
        let mut chunks: Vec<ChunkKey> = region.chunks.iter().copied().collect();
        chunks.sort_unstable_by_key(|c| (c.x, c.z));
        for chunk in chunks {
            self.refresh_demand(chunk, out);
        }
    }
}
