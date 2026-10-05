//! What a server keeps once for all its dimensions is the same on every node:
//! the scoreboard and teams, boss bars, command storage, stopwatches, `/tick`,
//! map ids, and who may join.

use std::thread::sleep;

use lodestar_e2e::cluster::int_after;
use lodestar_e2e::{Cluster, Defer, Node, secs, wait_for, wait_until};

/// Waits until every node answers a command with `expected` in its answer.
fn both(what: &str, nodes: &[&Node], command: &str, expected: &str) {
    wait_for(what, secs(5), || {
        let answers: Vec<String> = nodes.iter().map(|n| n.cmd(command)).collect();
        if answers.iter().all(|a| a.contains(expected)) {
            Ok(())
        } else {
            Err(format!("`{command}` answered {answers:?}"))
        }
    });
}

fn is_op(node: &Node, player: &str) -> bool {
    std::fs::read_to_string(node.dir.join("ops.json")).is_ok_and(|ops| ops.contains(&format!("\"{player}\"")))
}

fn stopwatch(node: &Node) -> Option<f64> {
    let reply = node.cmd("stopwatch query lodecore:e2e_race");
    let (_, rest) = reply.split_once("has run for ")?;
    rest.trim_end_matches('s').parse().ok()
}

pub fn every_node_has_the_same_cluster_data(cluster: &Cluster) {
    let nodes = cluster.live_nodes();
    let (a, b) = (&*nodes[0], &*nodes[1]);
    let all = [a, b];
    let _restore = Defer(|| {
        for node in all {
            node.cmds(&["tick sprint stop", "tick unfreeze", "tick rate 20", "lodecore unfreeze"]);
        }
        a.cmds(&[
            "scoreboard objectives remove e2e_kills",
            "scoreboard objectives remove e2e_race",
            "team remove e2e_red",
            "bossbar remove lodecore:e2e",
            "data remove storage lodecore:e2e numbers",
            "stopwatch remove lodecore:e2e_race",
            "whitelist remove e2ejoiner",
            "pardon-ip 10.9.8.7",
            "deop e2ejoiner",
        ]);
    });

    for (maker, other) in [(b, a), (a, b)] {
        eprintln!("e2e: changes made on {maker:?} reach {other:?}");
        maker.cmd("scoreboard objectives add e2e_kills dummy \"Kills\"");
        both("an objective", &all, "scoreboard objectives list", "[Kills]");
        maker.cmd("scoreboard players set Bob e2e_kills 7");
        both("a score", &all, "scoreboard players get Bob e2e_kills", "Bob has 7");
        maker.cmd("scoreboard players add Bob e2e_kills 3");
        both("a score added to", &all, "scoreboard players get Bob e2e_kills", "Bob has 10");
        maker.cmd("scoreboard objectives modify e2e_kills displayname \"Takedowns\"");
        both("an objective renamed", &all, "scoreboard objectives list", "[Takedowns]");
        maker.cmd("scoreboard objectives setdisplay sidebar e2e_kills");
        both("a display slot", &all, "scoreboard objectives setdisplay sidebar e2e_kills", "Nothing changed");
        maker.cmds(&["team add e2e_red", "team modify e2e_red color red", "team join e2e_red Bob"]);
        both("a team", &all, "team list", "e2e_red");
        both("a team member", &all, "team list e2e_red", "Bob");
        maker.cmd("team modify e2e_red friendlyFire false");
        both("a team option", &all, "team modify e2e_red friendlyFire false", "Nothing changed");
        maker.cmd("team leave Bob");
        both("a member leaving", &all, "team list e2e_red", "no members on team");
        maker.cmd("team remove e2e_red");
        both("a team removed", &all, "team list", "no teams");
        maker.cmd("scoreboard players reset Bob e2e_kills");
        both("a score reset", &all, "scoreboard players get Bob e2e_kills", "Can't get value");
        maker.cmd("scoreboard objectives remove e2e_kills");
        both("an objective removed", &all, "scoreboard objectives list", "no objectives");

        maker.cmd("bossbar add lodecore:e2e \"Siege\"");
        both("a boss bar", &all, "bossbar list", "[Siege]");
        maker.cmds(&["bossbar set lodecore:e2e max 40", "bossbar set lodecore:e2e value 25"]);
        both("its value", &all, "bossbar get lodecore:e2e value", "value of 25");
        both("and maximum", &all, "bossbar get lodecore:e2e max", "maximum of 40");
        maker.cmds(&["bossbar set lodecore:e2e color red", "bossbar set lodecore:e2e name \"Storm\""]);
        both("its name", &all, "bossbar list", "[Storm]");
        both("its colour", &all, "bossbar set lodecore:e2e color red", "Nothing changed");
        maker.cmd("bossbar remove lodecore:e2e");
        both("a boss bar removed", &all, "bossbar list", "no custom bossbars");

        maker.cmd("data modify storage lodecore:e2e numbers set value [1, 2, 3]");
        both("command storage", &all, "data get storage lodecore:e2e numbers", "[1, 2, 3]");
        maker.cmd("data modify storage lodecore:e2e numbers append value 4");
        both("command storage changed", &all, "data get storage lodecore:e2e numbers", "[1, 2, 3, 4]");
        maker.cmd("data remove storage lodecore:e2e numbers");
        both("command storage removed", &all, "data get storage lodecore:e2e", "{}");

        maker.cmd("stopwatch create lodecore:e2e_race");
        sleep(secs(2));
        let readings = (stopwatch(a), stopwatch(b));
        match readings {
            (Some(x), Some(y)) => assert!((x - y).abs() < 0.5, "stopwatches read {x} and {y}"),
            _ => panic!("a stopwatch is missing: {readings:?}"),
        }
        maker.cmd("stopwatch remove lodecore:e2e_race");
        both("a stopwatch removed", &all, "stopwatch query lodecore:e2e_race", "does not exist");

        // A name the server has never seen is looked up, and kept, in lower case.
        maker.cmd("whitelist add e2ejoiner");
        both("a player whitelisted", &all, "whitelist list", "e2ejoiner");
        maker.cmd("whitelist remove e2ejoiner");
        both("and taken off", &all, "whitelist list", "no whitelisted");
        maker.cmd("ban-ip 10.9.8.7");
        both("an address banned", &all, "banlist ips", "10.9.8.7");
        maker.cmd("pardon-ip 10.9.8.7");
        both("and pardoned", &all, "banlist ips", "no bans");
        maker.cmd("op e2ejoiner");
        wait_until("an operator on every node", secs(5), || all.iter().all(|n| is_op(n, "e2ejoiner")));
        maker.cmd("deop e2ejoiner");
        wait_until("and on none", secs(5), || !all.iter().any(|n| is_op(n, "e2ejoiner")));

        maker.cmd("tick rate 10");
        both("the tick rate", &all, "tick query", "Target tick rate: 10.0");
        sleep(secs(1));
        let start = (a.gametime(), b.gametime());
        sleep(secs(3));
        let ran = (a.gametime() - start.0, b.gametime() - start.1);
        assert!(
            (20..=36).contains(&ran.0) && (20..=36).contains(&ran.1),
            "at 10 ticks a second, A ran {} and B {} ticks in 3 s",
            ran.0,
            ran.1
        );
        maker.cmd("tick rate 20");
        maker.cmd("tick freeze");
        both("freezing", &all, "tick query", "The game is frozen");
        maker.cmd("tick unfreeze");
        both("unfreezing", &all, "tick query", "The game is running");
        let start = (a.gametime(), b.gametime());
        maker.cmd("tick sprint 600");
        sleep(secs(4));
        let ran = (a.gametime() - start.0, b.gametime() - start.1);
        // As fast as the slower node can, which is well over 80 ticks in 4 s.
        assert!(
            ran.0.min(ran.1) >= 160 && (ran.0 - ran.1).abs() <= 5,
            "sprinting, A ran {} and B {} ticks in 4 s",
            ran.0,
            ran.1
        );
        maker.cmd("tick sprint stop");
        both("and stops", &all, "tick query", "The game is running");
    }

    // The same score set on both nodes at once ends up the same on both.
    a.cmd("scoreboard objectives add e2e_race dummy");
    both("an objective", &all, "scoreboard objectives list", "e2e_race");
    a.cmd("scoreboard players set Race e2e_race 1");
    b.cmd("scoreboard players set Race e2e_race 2");
    wait_for("both nodes to agree on the score", secs(5), || {
        let answers = (a.cmd("scoreboard players get Race e2e_race"), b.cmd("scoreboard players get Race e2e_race"));
        if answers.0 == answers.1 { Ok(()) } else { Err(format!("{answers:?}")) }
    });

    // `/lodecore freeze` stops one node alone.
    b.cmd("lodecore freeze");
    sleep(secs(1));
    assert!(a.cmd("tick query").contains("The game is running"), "A stopped with B");
    assert!(b.cmd("tick query").contains("frozen"), "B is not frozen");
    b.cmd("lodecore unfreeze");
    both("B to run again", &all, "tick query", "The game is running");

    // No two nodes hand out the same map id.
    let mut ids = Vec::new();
    for (node, x) in [(a, -4800), (b, 4800)] {
        node.cmd(&format!("forceload add {x} 4800"));
        sleep(secs(1));
        node.cmds(&[
            &format!("setblock {x} 250 4800 chest"),
            &format!("item replace block {x} 250 4800 container.0 with minecraft:map"),
            &format!(
                "item modify block {x} 250 4800 container.0 {{type:\"minecraft:exploration_map\",\
                 destination:\"#minecraft:village\",decoration:\"minecraft:village_plains\",zoom:1b,\
                 search_radius:50,skip_existing_chunks:false}}"
            ),
        ]);
        let reply = node.cmd(&format!(
            "data get block {x} 250 4800 Items[0].components.\"minecraft:map_id\""
        ));
        let id = int_after(&reply, "data: ").unwrap_or_else(|| panic!("{node:?} made no map: {reply:?}"));
        ids.push(id);
        node.cmd(&format!("setblock {x} 250 4800 air"));
        node.cmd(&format!("forceload remove {x} 4800"));
    }
    assert_ne!(ids[0] / 4096, ids[1] / 4096, "map ids {ids:?} come from the same block");
}
