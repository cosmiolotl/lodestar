//! Every node runs the same ticks, at the pace of the slowest.

use std::thread::sleep;

use lodestar_e2e::{Cluster, Node, secs};

/// How many ticks each node runs in the given time.
fn advance(nodes: &[&Node], seconds: u64) -> Vec<i64> {
    let start: Vec<i64> = nodes.iter().map(|n| n.gametime()).collect();
    sleep(secs(seconds));
    nodes.iter().zip(start).map(|(n, s)| n.gametime() - s).collect()
}

pub fn nodes_tick_in_lockstep(cluster: &Cluster) {
    let nodes = cluster.live_nodes();
    let (a, b) = (&*nodes[0], &*nodes[1]);

    let ran = advance(&[a, b], 5);
    // The two nodes are asked a moment apart: allow a tick or two either way.
    assert!((ran[0] - ran[1]).abs() <= 2, "{a:?} ran {}, {b:?} ran {} ticks", ran[0], ran[1]);
    assert!((85..=105).contains(&ran[0]), "{} ticks in 5 s", ran[0]);

    // `/tick rate` on one node sets the pace of the whole cluster.
    b.cmd("tick rate 5");
    let slowed = std::panic::catch_unwind(|| {
        sleep(secs(1));
        advance(&[a, b], 5)
    });
    b.cmd("tick rate 20");
    let ran = slowed.unwrap();
    assert!(ran[0] <= 30, "{a:?} ran {} ticks in 5 s at 5 a second", ran[0]);
    assert!((ran[0] - ran[1]).abs() <= 2, "{a:?} ran {}, {b:?} ran {} ticks", ran[0], ran[1]);

    sleep(secs(1));
    let ran = advance(&[a, b], 3);
    assert!(
        ran[0] >= 50 && (ran[0] - ran[1]).abs() <= 2,
        "after recovering, {a:?} ran {}, {b:?} ran {} ticks in 3 s",
        ran[0],
        ran[1]
    );
}
