//! The time, weather, game rules, difficulty and world borders are the same on
//! every node: a change made on any node ends up on all of them.

use std::thread::sleep;

use lodestar_e2e::cluster::int_after;
use lodestar_e2e::{Cluster, Defer, Node, secs, wait_for};

fn clock(node: &Node) -> i64 {
    let reply = node.cmd("time of minecraft:overworld query time");
    int_after(&reply, "is at ").unwrap_or_else(|| panic!("{node:?} answered {reply:?}"))
}

fn border(node: &Node, dimension: &str) -> f64 {
    let reply = node.cmd(&format!("execute in {dimension} run worldborder get"));
    let (_, rest) = reply
        .split_once("currently ")
        .unwrap_or_else(|| panic!("{node:?} answered {reply:?}"));
    rest.split(' ').next().unwrap().parse().unwrap()
}

/// The word that follows `marker` in a command's answer.
fn word(node: &Node, command: &str, marker: &str) -> String {
    let reply = node.cmd(command);
    let (_, rest) = reply
        .split_once(marker)
        .unwrap_or_else(|| panic!("{node:?} answered `{command}` with {reply:?}"));
    rest.split(|c: char| !c.is_alphanumeric()).next().unwrap().to_lowercase()
}

fn difficulty(node: &Node) -> String {
    word(node, "difficulty", "difficulty is ")
}

fn keep_inventory(node: &Node) -> String {
    word(node, "gamerule keep_inventory", "set to ")
}

fn weather(node: &Node) -> &'static str {
    if node.passes("predicate {type:\"minecraft:weather_check\",thundering:true}") {
        "thunder"
    } else if node.passes("predicate {type:\"minecraft:weather_check\",raining:true}") {
        "rain"
    } else {
        "clear"
    }
}

/// Waits until every node reads the same, expected, value.
fn same<T: PartialEq + std::fmt::Debug>(what: &str, nodes: &[&Node], expected: T, read: impl Fn(&Node) -> T) {
    wait_for(what, secs(5), || {
        let values: Vec<T> = nodes.iter().map(|n| read(n)).collect();
        if values.iter().all(|v| *v == expected) {
            Ok(())
        } else {
            Err(format!("expected {expected:?}, nodes have {values:?}"))
        }
    });
}

pub fn every_node_has_the_same_world_state(cluster: &Cluster) {
    let nodes = cluster.live_nodes();
    let (a, b) = (&*nodes[0], &*nodes[1]);
    let both = [a, b];
    let start_clock = clock(a);
    let start_difficulty = difficulty(a);
    let start_keep = keep_inventory(a);
    let start_border = border(a, "minecraft:overworld");
    let start_nether = border(a, "minecraft:the_nether");
    let _restore = Defer(|| {
        a.cmds(&[
            &format!("difficulty {start_difficulty}"),
            &format!("gamerule keep_inventory {start_keep}"),
            &format!("worldborder set {start_border:.0}"),
            &format!("execute in minecraft:the_nether run worldborder set {start_nether:.0}"),
            "weather clear",
            "time resume",
            &format!("time set {start_clock}"),
        ]);
    });

    let apart = (a.gametime() - b.gametime()).abs();
    assert!(apart <= 2, "the game time is {apart} ticks apart");
    let apart = (clock(a) - clock(b)).abs();
    assert!(apart <= 2, "the time of day is {apart} ticks apart");
    assert_eq!(difficulty(a), difficulty(b));
    assert_eq!(keep_inventory(a), keep_inventory(b));
    assert_eq!(border(a, "minecraft:overworld"), border(b, "minecraft:overworld"));

    for (maker, other) in [(b, a), (a, b)] {
        eprintln!("e2e: changes made on {maker:?} reach {other:?}");
        let target = if maker.id == b.id { 6000 } else { 18000 };
        maker.cmd(&format!("time set {target}"));
        wait_for("the time of day", secs(5), || {
            let (at_a, at_b) = (clock(a), clock(b));
            if (at_a - at_b).abs() <= 2 && (target..target + 100).contains(&at_a) {
                Ok(())
            } else {
                Err(format!("set to {target}; A at {at_a}, B at {at_b}"))
            }
        });

        maker.cmd("time pause");
        sleep(secs(1));
        let first = (clock(a), clock(b));
        sleep(secs(1));
        assert_eq!(first, (clock(a), clock(b)), "a paused clock moved");
        assert_eq!(first.0, first.1, "a paused clock differs");
        maker.cmd("time resume");
        sleep(secs(1));
        let first = clock(a);
        sleep(secs(1));
        let (at_a, at_b) = (clock(a), clock(b));
        assert!(at_a > first && (at_a - at_b).abs() <= 2, "resumed: A went from {first} to {at_a}, B at {at_b}");

        let flipped = if keep_inventory(maker) == "true" { "false" } else { "true" };
        maker.cmd(&format!("gamerule keep_inventory {flipped}"));
        same("a game rule", &both, flipped.to_owned(), keep_inventory);

        let level = if maker.id == b.id { "hard" } else { "peaceful" };
        maker.cmd(&format!("difficulty {level}"));
        same("the difficulty", &both, level.to_owned(), difficulty);

        let width = if maker.id == b.id { 1234 } else { 4321 };
        maker.cmd(&format!("worldborder set {width}"));
        maker.cmd(&format!("execute in minecraft:the_nether run worldborder set {}", width / 2));
        same("the overworld's border", &both, f64::from(width), |n| border(n, "minecraft:overworld"));
        same("the nether's border", &both, f64::from(width / 2), |n| border(n, "minecraft:the_nether"));

        maker.cmd("worldborder set 3000");
        maker.cmd("worldborder set 2000 20s");
        sleep(secs(5));
        let (width_a, width_b) = (border(a, "minecraft:overworld"), border(b, "minecraft:overworld"));
        assert!(
            2000.0 < width_a && width_a < 3000.0 && (width_a - width_b).abs() <= 10.0,
            "a moving border: A {width_a}, B {width_b}"
        );

        // Rain and thunder take a few seconds to fade in or out.
        maker.cmd("weather thunder");
        wait_for("thunder everywhere", secs(10), || {
            let w = [weather(a), weather(b)];
            if w == ["thunder"; 2] { Ok(()) } else { Err(format!("{w:?}")) }
        });
        maker.cmd("weather clear");
        wait_for("clear skies everywhere", secs(10), || {
            let w = [weather(a), weather(b)];
            if w == ["clear"; 2] { Ok(()) } else { Err(format!("{w:?}")) }
        });
    }
}
