//! Players homed on different nodes see each other, and can hurt each other.

use lodestar_e2e::{Cluster, Defer, Node, offline_uuid, secs, wait_until};

const X: i32 = 1600;
const Y: i32 = 200;
const Z: i32 = 3200;

fn health(node: &Node, who: &str) -> Option<f64> {
    node.number(&format!("data get entity {who} Health"))
}

fn x_of(node: &Node, who: &str) -> Option<f64> {
    node.number(&format!("data get entity {who} Pos[0]"))
}

pub fn players_on_different_nodes_see_and_hurt_each_other(cluster: &Cluster) {
    let alex = cluster.join("PvpAlex");
    let steve = cluster.join("PvpSteve");
    let (at_alex, at_steve) = (cluster.home_of("PvpAlex").unwrap(), cluster.home_of("PvpSteve").unwrap());
    assert_ne!(at_alex.id, at_steve.id, "both players were placed on {at_alex:?}");
    let (alex_id, steve_id) = (offline_uuid("PvpAlex"), offline_uuid("PvpSteve"));
    let _unload = Defer(|| {
        at_alex.cmd("kill @e[tag=e2e_players]");
        at_alex.cmd(&format!("forceload remove {X} {Z}"));
    });

    at_alex.cmd(&format!("forceload add {X} {Z}"));
    at_alex.cmds(&[
        &format!("fill {} {} {} {} {} {} stone", X - 5, Y - 1, Z - 5, X + 10, Y - 1, Z + 10),
        &format!("fill {} {Y} {} {} {} {} air", X - 5, Z - 5, X + 10, Y + 4, Z + 10),
        &format!("tp PvpAlex {X} {Y} {Z}"),
    ]);
    at_steve.cmd(&format!("tp PvpSteve {} {Y} {Z}", X + 3));
    // A player is spared damage for a few seconds after arriving.
    std::thread::sleep(secs(4));

    wait_until("Steve to appear where Alex is", secs(8), || {
        x_of(&at_alex, &steve_id) == Some(f64::from(X) + 3.5)
    });
    wait_until("Alex to appear where Steve is", secs(8), || {
        x_of(&at_steve, &alex_id) == Some(f64::from(X) + 0.5)
    });
    assert!(!at_alex.has_player("PvpSteve"), "Steve is on the player list of Alex's node");
    alex.wait_for("Alex's client to be shown Steve", secs(10), |s| {
        s.sees_player(steve.uuid).ok_or_else(|| format!("{} entities", s.entities.len()))
    });
    steve.wait_for("Steve's client to be shown Alex", secs(10), |s| {
        s.sees_player(alex.uuid).ok_or_else(|| format!("{} entities", s.entities.len()))
    });

    // A remote player moves when its home node moves it.
    at_steve.cmd(&format!("tp PvpSteve {} {Y} {}", X + 6, Z + 4));
    wait_until("Steve to move where Alex is", secs(8), || {
        x_of(&at_alex, &steve_id) == Some(f64::from(X) + 6.5)
    });

    // Damage done to a remote player is done by their home node.
    let before = health(&at_steve, "PvpSteve").unwrap();
    at_alex.cmd(&format!("damage {steve_id} 4"));
    wait_until("Steve to be hurt on his own node", secs(8), || {
        health(&at_steve, "PvpSteve").is_some_and(|h| h < before)
    });
    wait_until("and Alex's node to show it", secs(8), || {
        health(&at_alex, &steve_id) == health(&at_steve, "PvpSteve")
    });

    // A mob simulated by one node hurts a player homed on the other.
    at_alex.cmd(&format!(
        "summon zombie {} {Y} {} {{NoAI:1b,Tags:[\"e2e_players\"]}}",
        X + 8,
        Z + 8
    ));
    std::thread::sleep(secs(1));
    let before = health(&at_steve, "PvpSteve").unwrap();
    at_alex.cmd(&format!("damage {steve_id} 2 minecraft:mob_attack by @e[tag=e2e_players,limit=1]"));
    wait_until("the zombie to hurt Steve", secs(8), || {
        health(&at_steve, "PvpSteve").is_some_and(|h| h < before)
    });

    // Alex's client hits what it was shown of Steve.
    at_steve.cmd(&format!("tp PvpSteve {} {Y} {Z}", X + 2));
    let shown = alex.wait_for("Alex's client to see Steve close by", secs(10), |s| {
        s.sees_player(steve.uuid).ok_or_else(|| "not shown".to_owned())
    });
    // Out of the time a player is spared after being hurt.
    std::thread::sleep(secs(2));
    let before = health(&at_steve, "PvpSteve").unwrap();
    alex.attack(shown);
    wait_until("Alex's punch to hurt Steve on his own node", secs(8), || {
        health(&at_steve, "PvpSteve").is_some_and(|h| h < before)
    });

    // A player who leaves goes from the other node, and from its players' screens.
    steve.quit();
    wait_until("Steve to go from where Alex is", secs(10), || x_of(&at_alex, &steve_id).is_none());
    alex.wait_for("Alex's client to be told Steve is gone", secs(10), |s| {
        match s.sees_player(lodeproxy::auth::offline_uuid("PvpSteve")) {
            None => Ok(()),
            Some(id) => Err(format!("still shown as {id}")),
        }
    });
    assert_eq!(alex.state().anomalies.counts(), [0; 4], "{:?}", alex.state().anomalies);
}
