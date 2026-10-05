//! What players on different nodes share at an event: boss bars, scores, and
//! the dragon fight.

use std::sync::Arc;
use std::thread::sleep;

use lodestar_e2e::cluster::int_after;
use lodestar_e2e::{Cluster, Defer, Node, secs, wait_for, wait_until};

fn on_bar(node: &Node) -> Vec<String> {
    let reply = node.cmd("bossbar get lodecore:e2e_crowd players");
    let mut names: Vec<String> = reply
        .split_once("online: ")
        .map(|(_, names)| names.split(", ").map(str::to_owned).collect())
        .unwrap_or_default();
    names.sort();
    names
}

pub fn players_on_different_nodes_share_an_event(cluster: &Cluster) {
    let _alex = cluster.join("EvAlex");
    let _steve = cluster.join("EvSteve");
    let at_alex = cluster.home_of("EvAlex").unwrap();
    let mut at_steve = cluster.home_of("EvSteve").unwrap();
    assert_ne!(at_alex.id, at_steve.id, "both players were placed on {at_alex:?}");
    let steve_from = at_steve.clone();
    let homes = || -> Vec<(&str, Arc<Node>)> {
        ["EvAlex", "EvSteve"]
            .into_iter()
            .filter_map(|name| cluster.home_of(name).map(|node| (name, node)))
            .collect()
    };
    let _restore = Defer(|| {
        at_alex.cmds(&["bossbar remove lodecore:e2e_crowd", "scoreboard objectives remove e2e_health"]);
    });

    // A score that a player's own node counts reaches the other node.
    sleep(secs(4));
    at_alex.cmds(&["scoreboard objectives add e2e_health health", "damage EvAlex 5"]);
    wait_for("Alex's health score to reach Steve's node", secs(10), || {
        let reply = at_steve.cmd("scoreboard players get EvAlex e2e_health");
        match int_after(&reply, "EvAlex has ") {
            Some(health) if health < 20 => Ok(()),
            _ => Err(reply),
        }
    });
    for (name, node) in homes() {
        node.cmd(&format!("gamemode creative {name}"));
    }
    at_alex.cmd("effect give EvAlex minecraft:instant_health 1 5");

    // A boss bar's players are the cluster's: each node sets which of its own
    // players are on it.
    at_alex.cmd("bossbar add lodecore:e2e_crowd \"Crowd\"");
    at_alex.cmd("bossbar set lodecore:e2e_crowd players @a");
    wait_until("Alex to be on the bar", secs(10), || on_bar(&at_alex) == ["EvAlex"]);
    sleep(secs(1));
    at_steve.cmd("bossbar set lodecore:e2e_crowd players @a");
    wait_until("Steve's node to put Steve on it", secs(10), || on_bar(&at_steve) == ["EvSteve"]);
    sleep(secs(1));
    assert_eq!(on_bar(&at_alex), ["EvAlex"], "Steve's node took Alex off the bar");

    // A player moved to another node is still on the bar there.
    at_steve.cmd(&format!("lodecore move EvSteve {}", at_alex.id));
    wait_until("Steve to move to Alex's node", secs(20), || at_alex.has_player("EvSteve"));
    wait_until("Steve to be on the bar there", secs(10), || on_bar(&at_alex) == ["EvAlex", "EvSteve"]);
    at_alex.cmd(&format!("lodecore move EvSteve {}", steve_from.id));
    wait_until("Steve to move back", secs(20), || steve_from.has_player("EvSteve"));
    at_steve = steve_from.clone();
    wait_until("Steve to be on the bar there", secs(10), || on_bar(&at_steve) == ["EvSteve"]);

    // The dragon fight is run by one node: two nodes with players at it have
    // one dragon between them.
    at_alex.cmd("execute in minecraft:the_end run tp EvAlex 30 90 30");
    at_steve.cmd("execute in minecraft:the_end run tp EvSteve -30 90 30");
    let in_end = |node: &Node| node.cmd("execute in minecraft:the_end if entity @e[type=minecraft:ender_dragon]");
    wait_until("a dragon where Alex is", secs(45), || in_end(&at_alex).starts_with("Test passed"));
    wait_until("and where Steve is", secs(45), || in_end(&at_steve).starts_with("Test passed"));
    sleep(secs(5));
    let count = |node: &Node| {
        let reply = in_end(node);
        if reply.starts_with("Test passed") { int_after(&reply, "Count: ").unwrap_or(1) } else { 0 }
    };
    assert_eq!((count(&at_alex), count(&at_steve)), (1, 1), "dragons where Alex and where Steve are");
    let uuid = |node: &Node| {
        node.cmd("execute in minecraft:the_end run data get entity @e[type=minecraft:ender_dragon,limit=1] UUID")
            .split_once("[I;")
            .map(|(_, rest)| rest.to_owned())
    };
    let uuids = (uuid(&at_alex), uuid(&at_steve));
    assert!(uuids.0.is_some() && uuids.0 == uuids.1, "the two nodes show different dragons: {uuids:?}");
}
