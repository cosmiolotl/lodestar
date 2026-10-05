//! A dimension is split between the nodes where its players are far apart, and
//! entities are simulated by the owner of where they are and mirrored on the
//! other nodes.

use lodestar_e2e::{Cluster, Defer, Node, Options, secs, wait_for, wait_until};

// Chunk x coordinates. Regions are 8 chunks wide, and regions no more than 2
// apart are one zone, so WEST (region -38) and EAST (region -34) are separate
// zones until BRIDGE (region -36) is loaded.
const WEST: i32 = -300 * 16;
const EAST: i32 = -272 * 16;
const BRIDGE: i32 = -288 * 16;
const Z: i32 = 2 * 16;
const Y: i32 = 200;

fn x_of(node: &Node, tag: &str) -> Option<f64> {
    node.number(&format!("data get entity @e[tag={tag},limit=1] Pos[0]"))
}

fn health(node: &Node, tag: &str) -> Option<f64> {
    node.number(&format!("data get entity @e[tag={tag},limit=1] Health"))
}

pub fn far_apart_areas_have_their_own_owners_and_merge(cluster: &Cluster) {
    let nodes = cluster.live_nodes();
    let (a, b) = (&*nodes[0], &*nodes[1]);
    let _restore = Defer(|| {
        for node in [a, b] {
            node.cmd("kill @e[tag=e2e_regions]");
            for x in [WEST, EAST, BRIDGE] {
                node.cmd(&format!("forceload remove {x} {Z}"));
            }
        }
    });

    a.cmd(&format!("forceload add {WEST} {Z}"));
    b.cmd(&format!("forceload add {EAST} {Z}"));
    wait_for("each area to be simulated by the node that loaded it", secs(10), || {
        let seen = [a.owner_of(WEST, Z), b.owner_of(WEST, Z), a.owner_of(EAST, Z), b.owner_of(EAST, Z)];
        if seen == [Some(a.id), Some(a.id), Some(b.id), Some(b.id)] {
            Ok(())
        } else {
            Err(format!("west as seen on A, B; east as seen on A, B: {seen:?}"))
        }
    });

    // A loads B's area too, as a replica.
    a.cmd(&format!("forceload add {EAST} {Z}"));
    std::thread::sleep(secs(2));
    assert_eq!(a.owner_of(EAST, Z), Some(b.id), "loading B's area made A its owner");
    b.cmds(&[
        &format!("fill {EAST} {} {Z} {} {} {} stone", Y - 1, EAST + 15, Y - 1, Z + 15),
        &format!("fill {EAST} {Y} {Z} {} {} {} air", EAST + 15, Y + 6, Z + 15),
    ]);
    let (ex, ez) = (EAST + 8, Z + 8);

    // An entity on the owner is mirrored on the replica.
    b.cmd(&format!(
        "summon zombie {ex} {Y} {ez} {{NoAI:1b,PersistenceRequired:1b,Tags:[\"e2e_regions\",\"e2e_zombie\"]}}"
    ));
    wait_until("the zombie to appear on A", secs(8), || a.count("@e[tag=e2e_zombie]") == 1);
    assert_eq!(b.count("@e[tag=e2e_zombie]"), 1);
    b.cmd(&format!("tp @e[tag=e2e_zombie] {} {Y} {ez}", ex + 3));
    wait_until("the zombie to move on A as B moves it", secs(8), || {
        x_of(a, "e2e_zombie").is_some_and(|x| x > f64::from(ex) + 2.5)
    });

    // The replica does not simulate its mirror: moving it there moves only its copy.
    a.cmd(&format!("tp @e[tag=e2e_zombie] {} {Y} {ez}", ex - 3));
    std::thread::sleep(secs(1));
    let x = x_of(b, "e2e_zombie");
    assert!(
        x.is_some_and(|x| (x - (f64::from(ex) + 3.5)).abs() < 0.6),
        "the owner's zombie moved to {x:?}"
    );

    // Damage done on the replica is done by the owner.
    let before = health(b, "e2e_zombie").expect("the zombie's health");
    a.cmd("damage @e[tag=e2e_zombie,limit=1] 5");
    wait_until("the owner's zombie to lose health", secs(8), || {
        health(b, "e2e_zombie").is_some_and(|h| h < before)
    });
    wait_until("the replica's copy to show it", secs(8), || {
        health(a, "e2e_zombie") == health(b, "e2e_zombie")
    });

    // An entity made on the replica is made by the owner, once.
    a.cmd(&format!(
        "summon pig {ex} {Y} {} {{NoAI:1b,PersistenceRequired:1b,Tags:[\"e2e_regions\",\"e2e_pig\"]}}",
        ez + 3
    ));
    wait_until("the pig to exist on B", secs(8), || b.count("@e[tag=e2e_pig]") == 1);
    wait_until("and on A", secs(8), || a.count("@e[tag=e2e_pig]") == 1);
    std::thread::sleep(secs(1));
    assert_eq!(
        (a.count("@e[tag=e2e_pig]"), b.count("@e[tag=e2e_pig]")),
        (1, 1),
        "pigs on A and B"
    );
    b.cmd("kill @e[tag=e2e_pig]");
    wait_until("the dead pig to go from A", secs(8), || a.count("@e[tag=e2e_pig]") == 0);

    // Loading the ground between the two areas merges them into one zone.
    b.cmd(&format!(
        "summon armor_stand {ex} {Y} {} {{Tags:[\"e2e_regions\",\"e2e_stand\"]}}",
        ez - 3
    ));
    b.cmd(&format!("setblock {ex} {Y} {} gold_block", ez + 5));
    wait_until("the stand to reach A", secs(8), || a.count("@e[tag=e2e_stand]") == 1);
    b.cmd(&format!("forceload add {BRIDGE} {Z}"));
    let owner = wait_for("the areas to end up with one owner", secs(30), || {
        let owners: Vec<Option<u32>> = [a, b]
            .iter()
            .flat_map(|n| [WEST, EAST, BRIDGE].map(|x| n.owner_of(x, Z)))
            .collect();
        match owners[0] {
            Some(owner) if owners.iter().all(|o| *o == Some(owner)) => Ok(owner),
            _ => Err(format!("{owners:?}")),
        }
    });
    let (winner, loser) = if owner == a.id { (a, b) } else { (b, a) };
    assert_eq!(
        (a.count("@e[tag=e2e_zombie]"), b.count("@e[tag=e2e_zombie]")),
        (1, 1),
        "zombies on A and B after the merge"
    );
    for node in [a, b] {
        assert!(node.has_block(ex, Y, ez + 5, "gold_block"), "{node:?} lost the gold block");
    }
    winner.cmd(&format!("tp @e[tag=e2e_zombie] {} {Y} {ez}", ex + 5));
    wait_until(&format!("{winner:?}, the new owner, to move the zombie for both"), secs(8), || {
        x_of(loser, "e2e_zombie").is_some_and(|x| (x - (f64::from(ex) + 5.5)).abs() < 0.6)
    });
    let before = health(winner, "e2e_zombie").expect("the zombie's health");
    loser.cmd("damage @e[tag=e2e_zombie,limit=1] 3");
    wait_until(&format!("damage done on {loser:?} to reach {winner:?}"), secs(8), || {
        health(winner, "e2e_zombie").is_some_and(|h| h < before)
    });
}

/// A node that leaves hands what it simulated to a node that has it loaded,
/// which simulates it from then on, as the first node left it.
pub fn a_leaving_node_hands_its_area_to_a_node_that_has_it() {
    const X: i32 = 40 * 16;
    const Z: i32 = 40 * 16;
    let cluster = Cluster::start("hand-over", Options { proxy: false, ..Options::default() });
    let nodes = cluster.live_nodes();
    let (staying, leaving) = (&*nodes[0], &*nodes[1]);

    crate::blocks::own(leaving, X, Z);
    leaving.cmds(&[
        &format!("fill {X} {Y} {Z} {} {} {} air", X + 15, Y + 8, Z + 15),
        &format!("fill {X} {} {Z} {} {} {} stone", Y - 1, X + 15, Y - 1, Z + 15),
        &format!("setblock {} {Y} {} gold_block", X + 4, Z + 4),
        &format!(
            "summon zombie {} {Y} {} {{NoAI:1b,PersistenceRequired:1b,Tags:[\"e2e_hand_over\"]}}",
            X + 8,
            Z + 8
        ),
    ]);
    staying.cmd(&format!("forceload add {X} {Z}"));
    wait_until("the gold block to reach the staying node", secs(10), || {
        staying.has_block(X + 4, Y, Z + 4, "gold_block")
    });
    wait_until("the zombie to reach the staying node", secs(10), || staying.count("@e[tag=e2e_hand_over]") == 1);
    assert_eq!(staying.owner_of(X, Z), Some(leaving.id), "the staying node took the area early");

    assert!(leaving.stop().success(), "the leaving node did not stop cleanly");
    wait_for("the staying node to own the area", secs(30), || match staying.owner_of(X, Z) {
        Some(id) if id == staying.id => Ok(()),
        other => Err(format!("owned by {other:?}")),
    });
    assert!(cluster.wait_star_exit(secs(2)).is_none(), "lodestar stopped when a node left cleanly");

    // It simulates the area now, with what was in it.
    staying.cmd(&format!("setblock {} {} {} sand", X + 6, Y + 5, Z + 6));
    wait_until("the sand to fall", secs(8), || staying.has_block(X + 6, Y, Z + 6, "sand"));
    assert!(staying.has_block(X + 4, Y, Z + 4, "gold_block"), "the gold block went with the node");
    assert_eq!(staying.count("@e[tag=e2e_hand_over]"), 1, "zombies after the hand-over");
    let before = health(staying, "e2e_hand_over").expect("the zombie's health");
    staying.cmd("damage @e[tag=e2e_hand_over,limit=1] 5");
    wait_until("the zombie, simulated here now, to be hurt", secs(8), || {
        health(staying, "e2e_hand_over").is_some_and(|h| h < before)
    });
}
