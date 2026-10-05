use super::*;

impl State {
    pub fn new(policy: Policy) -> State {
        State {
            tick_interval: policy.tick_interval,
            policy,
            peers: HashMap::new(),
            nodes: HashMap::new(),
            next_node: NO_NODE + 1,
            dims: Vec::new(),
            chunks: HashMap::new(),
            regions: HashMap::new(),
            history: HashMap::new(),
            retired: Vec::new(),
            handovers: Vec::new(),
            replan: false,
            last_plan: 0,
            clock: Clock {
                tick: 0,
                due: None,
                started: None,
                pending: HashSet::new(),
                stats: TickStats::default(),
            },
            checkpoint: checkpoint::Checkpoint::default(),
            players: HashMap::new(),
            reservations: HashMap::new(),
            custody: HashMap::new(),
            claims: HashMap::new(),
            player_data: HashMap::new(),
            data_custody: HashMap::new(),
            data_waiting: Vec::new(),
            handoffs: HashMap::new(),
            last_handoff_plan: 0,
            next_id_block: FIRST_ID_BLOCK,
            next_counter_blocks: [0; COUNTERS as usize],
            masters: HashMap::new(),
        }
    }

    /// Picks up the entity id blocks where a previous run left off, so that
    /// ids entities may still have are not handed out again.
    pub fn load_id_blocks(&mut self, next: u32) {
        self.next_id_block = next.clamp(FIRST_ID_BLOCK, LAST_ID_BLOCK);
    }

    pub(super) fn id_block_request(&mut self, node: NodeId, out: &mut Vec<Effect>) {
        let block = self.next_id_block;
        self.next_id_block = if block >= LAST_ID_BLOCK {
            FIRST_ID_BLOCK
        } else {
            block + 1
        };
        out.push(Effect::StoreIdBlocks {
            next: self.next_id_block,
        });
        send(out, self.nodes[&node].conn, &Message::IdBlock { block });
    }

    /// Picks up the counters' blocks where a previous run left off. Counters
    /// that were not written down start from their first block.
    pub fn load_counter_blocks(&mut self, next: &[u32]) {
        for (slot, next) in self.next_counter_blocks.iter_mut().zip(next) {
            *slot = (*next).min(LAST_COUNTER_BLOCK);
        }
    }

    /// Hands out the next block of a counter, past every id the node may
    /// already have handed out on its own. Nodes keep their saves while
    /// lodestar comes and goes, so `floor` keeps a fresh lodestar clear of the
    /// ids already in them.
    pub(super) fn counter_block_request(
        &mut self,
        node: NodeId,
        counter: u8,
        floor: u32,
        out: &mut Vec<Effect>,
    ) {
        let Some(next) = self.next_counter_blocks.get_mut(counter as usize) else {
            warn!(
                "node {} asked for a block of unknown counter {counter}",
                self.nodes[&node].name
            );
            return;
        };
        let block = (*next)
            .max(floor.div_ceil(1 << COUNTER_BLOCK_SHIFT))
            .min(LAST_COUNTER_BLOCK);
        if block == LAST_COUNTER_BLOCK {
            warn!("counter {counter} has run out of ids; its last block is handed out again");
        }
        *next = (block + 1).min(LAST_COUNTER_BLOCK);
        out.push(Effect::StoreCounterBlocks {
            next: self.next_counter_blocks,
        });
        send(
            out,
            self.nodes[&node].conn,
            &Message::CounterBlock { counter, block },
        );
    }

    /// The master of the cluster's state says how long a tick should take.
    pub(super) fn tick_interval_request(&mut self, node: NodeId, nanos: u64) {
        if self.master_of(CLUSTER_SCOPE) != node {
            return;
        }
        let interval = Duration::from_nanos(nanos).min(LONGEST_TICK);
        if interval != self.tick_interval {
            info!("ticks take {interval:?} from now on, as the cluster's master asks");
            self.tick_interval = interval;
        }
    }
}
