//! Block changes, and what comes of them, reach every node that has the chunk.
//!
//! The node that loads an area first owns it and simulates it; the others hold
//! replicas, which take the owner's changes and send theirs to the owner.

use std::thread::sleep;
use std::time::Duration;

use lodestar_e2e::{Cluster, Defer, Node, secs, stays, wait_for, wait_until};

fn expect_block(node: &Node, (x, y, z): (i32, i32, i32), block: &str, timeout: Duration) {
    wait_until(&format!("{block} at {x} {y} {z} on {node:?}"), timeout, || {
        node.has_block(x, y, z, block)
    });
}

/// Force-loads the chunk at x, z on `node` first, so that it becomes the owner.
pub fn own(node: &Node, x: i32, z: i32) {
    node.cmd(&format!("forceload add {x} {z}"));
    wait_for(&format!("{node:?} to own {x} {z}"), secs(10), || {
        match node.owner_of(x, z) {
            Some(id) if id == node.id => Ok(()),
            other => Err(format!("owned by {other:?}")),
        }
    });
}

pub fn changes_reach_every_node(cluster: &Cluster) {
    const X: i32 = 1600;
    const Z: i32 = 1600;
    let nodes = cluster.live_nodes();
    let (owner, replica) = (&*nodes[0], &*nodes[1]);
    let _unload = Defer(|| {
        for node in [owner, replica] {
            node.cmd(&format!("forceload remove {X} {Z}"));
        }
    });

    own(owner, X, Z);
    owner.cmds(&[
        &format!("fill {X} 195 {Z} {} 210 {} air", X + 15, Z + 15),
        &format!("fill {X} 199 {Z} {} 199 {} stone", X + 15, Z + 15),
        &format!("setblock {} 200 {} gold_block", X + 8, Z + 8),
    ]);

    // The replica loads the chunk and takes on the owner's copy of it.
    replica.cmd(&format!("forceload add {X} {Z}"));
    expect_block(replica, (X + 8, 200, Z + 8), "gold_block", secs(10));
    expect_block(replica, (X + 15, 199, Z + 15), "stone", secs(10));

    owner.cmd(&format!("setblock {} 200 {} diamond_block", X + 5, Z + 5));
    expect_block(replica, (X + 5, 200, Z + 5), "diamond_block", secs(6));

    replica.cmd(&format!("setblock {} 200 {} emerald_block", X + 6, Z + 6));
    expect_block(owner, (X + 6, 200, Z + 6), "emerald_block", secs(6));
    owner.cmd(&format!("setblock {} 200 {} air", X + 6, Z + 6));
    expect_block(replica, (X + 6, 200, Z + 6), "air", secs(6));

    // What a replica's change leads to happens once, on the owner.
    replica.cmd(&format!("setblock {} 205 {} sand", X + 7, Z + 7));
    for node in [owner, replica] {
        expect_block(node, (X + 7, 200, Z + 7), "sand", secs(6));
        expect_block(node, (X + 7, 205, Z + 7), "air", secs(6));
    }
    // Water heads for the nearest drop: the platform's western edge.
    replica.cmd(&format!("setblock {} 200 {} water", X + 2, Z + 12));
    for node in [owner, replica] {
        expect_block(node, (X + 1, 200, Z + 12), "water", secs(10));
    }
}

pub fn only_the_owner_ticks_what_it_owns(cluster: &Cluster) {
    const X: i32 = 3200;
    const Z: i32 = 1600;
    let nodes = cluster.live_nodes();
    let (owner, replica) = (&*nodes[0], &*nodes[1]);
    let _restore = Defer(|| {
        owner.cmd("lodecore unfreeze");
        owner.cmd("gamerule random_tick_speed 3");
        for node in [owner, replica] {
            node.cmd(&format!("forceload remove {X} {Z}"));
        }
    });

    own(owner, X, Z);
    owner.cmds(&[
        &format!("fill {X} 195 {Z} {} 210 {} air", X + 15, Z + 15),
        &format!("fill {X} 199 {Z} {} 199 {} stone", X + 15, Z + 15),
        &format!("setblock {} 200 {} farmland[moisture=7]", X + 12, Z + 3),
        &format!("setblock {} 202 {} glowstone", X + 12, Z + 3),
    ]);
    replica.cmd(&format!("forceload add {X} {Z}"));
    expect_block(replica, (X + 12, 202, Z + 3), "glowstone", secs(10));

    // Wheat grows on random ticks, here at a furious rate. With the owner
    // alone frozen, it grows only if a node that does not own it ticks it.
    owner.cmd("lodecore freeze");
    owner.cmd(&format!("setblock {} 201 {} wheat[age=0]", X + 12, Z + 3));
    owner.cmd("gamerule random_tick_speed 3000");
    expect_block(replica, (X + 12, 201, Z + 3), "wheat[age=0]", secs(6));
    stays("the wheat not growing on the replica", secs(4), || {
        if replica.has_block(X + 12, 201, Z + 3, "wheat[age=0]") {
            Ok(())
        } else {
            Err("it grew".into())
        }
    });
    owner.cmd("lodecore unfreeze");

    // The owner lets go of the chunk, but keeps it loaded and ticking for the
    // replica that still has it.
    owner.cmd(&format!("forceload remove {X} {Z}"));
    expect_block(replica, (X + 12, 201, Z + 3), "wheat[age=7]", secs(20));
    replica.cmd(&format!("setblock {} 205 {} sand", X + 9, Z + 9));
    expect_block(replica, (X + 9, 200, Z + 9), "sand", secs(6));

    owner.cmd(&format!("forceload add {X} {Z}"));
    expect_block(owner, (X + 9, 200, Z + 9), "sand", secs(10));
}

/// Two chunks next to each other, each loaded by a different node, are one
/// zone, which one node simulates: updates cross from one into the other as
/// on a single server.
pub fn updates_cross_between_chunks_of_different_nodes(cluster: &Cluster) {
    const AX: i32 = 4800;
    const BX: i32 = AX + 16;
    const Z: i32 = 1600;
    // The last column of A's chunk, and the first of B's.
    const WEST: i32 = AX + 15;
    const EAST: i32 = BX;
    let nodes = cluster.live_nodes();
    let (a, b) = (&*nodes[0], &*nodes[1]);
    let _unload = Defer(|| {
        a.cmd(&format!("forceload remove {AX} {Z}"));
        b.cmd(&format!("forceload remove {BX} {Z}"));
    });

    a.cmd(&format!("forceload add {AX} {Z}"));
    b.cmd(&format!("forceload add {BX} {Z}"));
    wait_for("both chunks to have one owner", secs(10), || {
        let owners = [a.owner_of(AX, Z), a.owner_of(BX, Z), b.owner_of(AX, Z), b.owner_of(BX, Z)];
        if owners[0].is_some() && owners.iter().all(|o| *o == owners[0]) {
            Ok(())
        } else {
            Err(format!("{owners:?}"))
        }
    });
    a.cmds(&[
        &format!("fill {AX} 195 {Z} {} 210 {} air", BX + 15, Z + 15),
        &format!("fill {AX} 199 {Z} {} 199 {} stone", BX + 15, Z + 15),
        // A channel across the two chunks, closed at its western end.
        &format!("fill {} 200 {} {} 200 {} stone", WEST - 4, Z + 7, EAST + 4, Z + 7),
        &format!("fill {} 200 {} {} 200 {} stone", WEST - 4, Z + 9, EAST + 4, Z + 9),
        &format!("setblock {} 200 {} stone", WEST - 4, Z + 8),
    ]);
    expect_block(b, (EAST + 4, 200, Z + 9), "stone", secs(10));

    a.cmd(&format!("setblock {} 200 {} water", WEST - 3, Z + 8));
    for node in [a, b] {
        expect_block(node, (EAST + 2, 200, Z + 8), "water", secs(12));
    }

    // Switching off is a scheduled tick, queued by an update from the other
    // chunk: what got stuck when neighbouring chunks had different owners.
    b.cmd(&format!("setblock {EAST} 200 {} redstone_lamp", Z + 2));
    expect_block(a, (EAST, 200, Z + 2), "redstone_lamp", secs(6));
    for switch in [a, b] {
        switch.cmd(&format!("setblock {WEST} 200 {} redstone_block", Z + 2));
        for node in [a, b] {
            expect_block(node, (EAST, 200, Z + 2), "redstone_lamp[lit=true]", secs(8));
        }
        switch.cmd(&format!("setblock {WEST} 200 {} air", Z + 2));
        for node in [a, b] {
            expect_block(node, (EAST, 200, Z + 2), "redstone_lamp[lit=false]", secs(8));
        }
    }
}

/// A player places and breaks blocks, with a client, on ground another node
/// simulates; a player of that node sees it happen.
pub fn a_player_builds_where_another_node_simulates(cluster: &Cluster) {
    const X: i32 = 4800;
    const Z: i32 = 3200;
    let builder = cluster.join("Builder");
    let watcher = cluster.join("Watcher");
    let (b, a) = (cluster.home_of("Builder").unwrap(), cluster.home_of("Watcher").unwrap());
    assert_ne!(a.id, b.id, "both players were placed on {a:?}");
    let _unload = Defer(|| {
        a.cmd(&format!("forceload remove {X} {Z}"));
    });

    own(&a, X, Z);
    a.cmds(&[
        &format!("fill {} 199 {} {} 199 {} stone", X - 6, Z - 6, X + 6, Z + 6),
        &format!("fill {} 200 {} {} 204 {} air", X - 6, Z - 6, X + 6, Z + 6),
        &format!("tp Watcher {}.5 200 {}.5", X + 3, Z),
    ]);
    b.cmds(&[
        "gamemode creative Builder",
        "item replace entity Builder weapon.mainhand with minecraft:stone",
        &format!("tp Builder {X}.5 200 {Z}.5"),
    ]);
    let chunk = (X >> 4, Z >> 4);
    for client in [&builder, &watcher] {
        client.wait_for("the stage's chunk to arrive", secs(30), |s| {
            if s.chunks.contains(&chunk) && s.position.is_some_and(|p| (p[0] - f64::from(X)).abs() < 8.0) {
                Ok(())
            } else {
                Err(format!("at {:?}, {} chunks", s.position, s.chunks.len()))
            }
        });
    }
    // A player is let act once their client has caught up with the teleport.
    sleep(secs(1));

    let placed = (X + 1, 200, Z + 1);
    builder.use_item_on(placed.0, placed.1 - 1, placed.2);
    expect_block(&a, placed, "stone", secs(8));
    expect_block(&b, placed, "stone", secs(8));
    watcher.wait_for("the watcher's client to be shown the block", secs(8), |s| {
        match s.blocks.get(&placed) {
            Some(&state) if state != 0 => Ok(()),
            other => Err(format!("{other:?}")),
        }
    });

    builder.start_destroy(placed.0, placed.1, placed.2);
    expect_block(&a, placed, "air", secs(8));
    expect_block(&b, placed, "air", secs(8));
    watcher.wait_for("the watcher's client to be shown it go", secs(8), |s| {
        match s.blocks.get(&placed) {
            Some(0) => Ok(()),
            other => Err(format!("{other:?}")),
        }
    });
    b.cmd("gamemode survival Builder");
}
