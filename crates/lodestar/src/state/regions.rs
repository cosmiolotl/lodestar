use super::*;

impl State {
    /// Runs between two ticks: finishes the handovers that are ready, then
    /// works out who should own each zone, then tells the workers what changed.
    pub(super) fn plan(&mut self, out: &mut Vec<Effect>) {
        let tick = self.clock.tick;
        self.progress_handovers(tick, out);
        if self.replan || tick >= self.last_plan + REPLAN_INTERVAL_TICKS {
            self.replan = false;
            self.last_plan = tick;
            for zone in zones::zones(self.regions.keys().copied()) {
                self.settle(&zone, tick, out);
            }
        }
        self.announce(out);
    }

    pub(super) fn progress_handovers(&mut self, tick: u64, out: &mut Vec<Effect>) {
        let mut handovers = std::mem::take(&mut self.handovers);
        handovers.retain_mut(|h| {
            // Regions can go out of use, or be handed elsewhere, meanwhile.
            h.regions.retain(|key| {
                self.regions
                    .get(key)
                    .is_some_and(|r| r.owner == h.from && r.incoming == h.to)
            });
            if h.regions.is_empty() {
                return false;
            }
            if self.caught_up(h.to, &h.regions) {
                info!(
                    "node #{} takes over {} regions from node #{}",
                    h.to,
                    h.regions.len(),
                    h.from
                );
                for key in &h.regions {
                    let region = self.regions.get_mut(key).expect("checked above");
                    region.owner = h.to;
                    region.incoming = NO_NODE;
                    region.since = tick;
                }
            } else if tick >= h.started + HANDOVER_TIMEOUT_TICKS {
                warn!(
                    "node #{} did not catch up with {} regions of node #{} in time, giving up",
                    h.to,
                    h.regions.len(),
                    h.from
                );
                for key in &h.regions {
                    self.regions.get_mut(key).expect("checked above").incoming = NO_NODE;
                }
            } else {
                return true;
            }
            for key in &h.regions {
                self.refresh_region_demand(*key, out);
            }
            self.replan = true;
            false
        });
        handovers.append(&mut self.handovers);
        self.handovers = handovers;
    }

    /// Whether `node` has a synced copy of every chunk in use in `regions`.
    pub(super) fn caught_up(&self, node: NodeId, regions: &[RegionKey]) -> bool {
        regions.iter().all(|key| {
            self.regions[key].chunks.iter().all(|chunk| {
                self.chunks
                    .get(chunk)
                    .is_some_and(|c| c.subs.contains(&node) && c.synced.contains(&node))
            })
        })
    }

    /// Makes sure a zone has an owner, and works towards it having exactly one.
    pub(super) fn settle(&mut self, zone: &[RegionKey], tick: u64, out: &mut Vec<Effect>) {
        let dim = zone[0].dim;
        if self
            .dims
            .get(dim as usize)
            .is_none_or(|d| d.workers.is_empty())
        {
            return;
        }

        // Nobody has a copy to wait for of a new region, or of one whose owner
        // left, so these are given out straight away.
        let unowned: Vec<RegionKey> = zone
            .iter()
            .copied()
            .filter(|key| self.regions[key].owner == NO_NODE)
            .collect();
        if !unowned.is_empty() {
            let owner = self.owner_for_unowned(zone, &unowned);
            for key in &unowned {
                let region = self.regions.get_mut(key).expect("part of the zone");
                region.owner = owner;
                region.since = tick;
            }
            for key in &unowned {
                self.refresh_region_demand(*key, out);
            }
        }

        let target = self.best_owner(zone);
        for key in zone {
            let region = self.regions.get_mut(key).expect("part of the zone");
            if region.incoming != NO_NODE && region.incoming != target {
                region.incoming = NO_NODE;
                self.refresh_region_demand(*key, out);
            }
        }
        let owners: BTreeMap<NodeId, usize> = self.count_owners(zone);
        if owners.len() == 1 && owners.contains_key(&target) {
            return;
        }
        if owners.len() == 1 {
            // A zone with one owner is only moved for the sake of locality, and
            // only when that is clearly better, so that ownership does not
            // bounce between nodes as players come and go.
            let owner = *owners.keys().next().expect("one owner");
            let cooled = zone
                .iter()
                .all(|key| tick >= self.regions[key].since + REBALANCE_COOLDOWN_TICKS);
            if !cooled || self.players_in(target, zone) <= 2 * self.players_in(owner, zone) {
                return;
            }
        }

        let mut by_owner: BTreeMap<NodeId, Vec<RegionKey>> = BTreeMap::new();
        for key in zone {
            let region = &self.regions[key];
            if region.owner != target && region.incoming != target {
                by_owner.entry(region.owner).or_default().push(*key);
            }
        }
        for (from, regions) in by_owner {
            info!(
                "handing {} regions of {} from node #{from} to node #{target}",
                regions.len(),
                self.dims[dim as usize].name
            );
            for key in &regions {
                self.regions
                    .get_mut(key)
                    .expect("part of the zone")
                    .incoming = target;
            }
            for key in &regions {
                self.refresh_region_demand(*key, out);
            }
            self.handovers.push(Handover {
                from,
                to: target,
                regions,
                started: tick,
            });
        }
    }

    /// Who gets regions nobody owns. A region that was in use before goes back
    /// to its last owner, whose save is likely the freshest; otherwise it joins
    /// whoever already owns most of the zone.
    pub(super) fn owner_for_unowned(&self, zone: &[RegionKey], unowned: &[RegionKey]) -> NodeId {
        let dim = zone[0].dim;
        let mut votes: BTreeMap<NodeId, usize> = BTreeMap::new();
        for key in unowned {
            // A region someone holds a synced copy of has lost its owner, and
            // that copy is newer than any save.
            let orphaned = self.regions[key].chunks.iter().any(|c| {
                self.chunks
                    .get(c)
                    .is_some_and(|chunk| !chunk.synced.is_empty())
            });
            if let Some(&last) = self.history.get(key)
                && !orphaned
                && self.is_worker(last, dim)
            {
                *votes.entry(last).or_default() += 1;
            }
        }
        if votes.is_empty() {
            votes = self.count_owners(zone);
        }
        votes
            .into_iter()
            .max_by_key(|&(node, count)| (count, Reverse(node)))
            .map_or_else(|| self.best_owner(zone), |(node, _)| node)
    }

    /// The worker best placed to simulate a zone: the one with most of its
    /// players, since they are what it is simulated for, and then the one
    /// that has least to catch up with.
    pub(super) fn best_owner(&self, zone: &[RegionKey]) -> NodeId {
        let dim = zone[0].dim as usize;
        let chunks_where = |test: &dyn Fn(&Chunk) -> bool| {
            zone.iter()
                .flat_map(|key| self.regions[key].chunks.iter())
                .filter(|chunk| self.chunks.get(chunk).is_some_and(test))
                .count()
        };
        self.dims[dim]
            .workers
            .iter()
            .copied()
            .max_by_key(|&node| {
                (
                    self.players_in(node, zone),
                    zone.iter()
                        .filter(|key| self.regions[key].owner == node)
                        .count(),
                    chunks_where(&|c: &Chunk| c.synced.contains(&node)),
                    chunks_where(&|c: &Chunk| c.subs.contains(&node)),
                    Reverse(self.regions.values().filter(|r| r.owner == node).count()),
                    Reverse(node),
                )
            })
            .unwrap_or(NO_NODE)
    }

    pub(super) fn count_owners(&self, zone: &[RegionKey]) -> BTreeMap<NodeId, usize> {
        let mut owners = BTreeMap::new();
        for key in zone {
            let owner = self.regions[key].owner;
            if owner != NO_NODE {
                *owners.entry(owner).or_default() += 1;
            }
        }
        owners
    }

    /// How many of a node's players are in a zone, which is sorted.
    pub(super) fn players_in(&self, node: NodeId, zone: &[RegionKey]) -> usize {
        self.nodes.get(&node).map_or(0, |n| {
            n.players
                .iter()
                .filter_map(|uuid| self.players.get(uuid)?.at)
                .filter(|at| zone.binary_search(&at.region()).is_ok())
                .count()
        })
    }

    pub(super) fn is_worker(&self, node: NodeId, dim: u32) -> bool {
        self.dims
            .get(dim as usize)
            .is_some_and(|d| d.workers.contains(&node))
    }

    /// Tells the workers of each dimension about every region whose owner has
    /// changed since they were last told.
    pub(super) fn announce(&mut self, out: &mut Vec<Effect>) {
        let mut retired = std::mem::take(&mut self.retired);
        retired.sort_unstable();
        retired.dedup();
        for region in retired {
            if !self.regions.contains_key(&region) {
                let msg = Message::RegionOwner {
                    region,
                    owner: NO_NODE,
                };
                self.tell_workers(region.dim, &msg, out);
            }
        }
        let mut changed: Vec<RegionKey> = self
            .regions
            .iter()
            .filter(|(_, r)| r.owner != r.announced)
            .map(|(key, _)| *key)
            .collect();
        changed.sort_unstable();
        for key in changed {
            let region = self.regions.get_mut(&key).expect("just listed");
            region.announced = region.owner;
            let msg = Message::RegionOwner {
                region: key,
                owner: region.owner,
            };
            self.tell_workers(key.dim, &msg, out);
        }
    }
}
