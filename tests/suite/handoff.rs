//! Connected players move between nodes without their clients noticing.

use std::thread::sleep;
use std::time::Instant;

use lodestar_e2e::{Client, Cluster, Defer, Node, Options, secs, wait_until};

const X: i32 = 1600;
const Y: i32 = 200;
const Z: i32 = -1600;

fn platform(node: &Node) {
    node.cmd(&format!("forceload add {X} {Z}"));
    node.cmds(&[
        &format!("fill {} {} {} {} {} {} stone", X - 12, Y - 1, Z - 12, X + 12, Y - 1, Z + 12),
        &format!("fill {} {Y} {} {} {} {} air", X - 12, Z - 12, X + 12, Y + 3, Z + 12),
    ]);
}

/// Moves `player` from one node to another, and checks that their client,
/// and another player's, noticed nothing.
fn move_player(player: &Client, observer: &Client, from: &Node, to: &Node) {
    let name = &player.name;
    eprintln!("e2e: moving {name} from {from:?} to {to:?}");
    let before = player.state().anomalies.clone();
    let observer_before = observer.state().anomalies.clone();
    let shown_as = observer.state().sees_player(player.uuid);
    let id = from.entity_id(name).expect("the player's entity id");
    let started = Instant::now();
    from.cmd(&format!("lodecore move {name} {}", to.id));
    wait_until(&format!("{name} to be homed on {to:?}"), secs(20), || to.has_player(name));
    eprintln!("e2e: moved in {:.1?}", started.elapsed());

    assert!(!from.has_player(name), "{name} is still on {from:?}'s player list");
    assert!(player.connected(), "{name}'s client was disconnected");
    assert_eq!(to.entity_id(name), Some(id), "{name}'s entity id changed");
    let inventory = to.cmd(&format!("data get entity {name} Inventory"));
    assert!(inventory.contains("minecraft:diamond"), "{name} lost his diamonds: {inventory}");
    assert!(
        to.passes(&format!("entity @a[name={name},advancements={{minecraft:story/root=true}}]")),
        "{name} lost his advancement"
    );
    // The client paces three blocks either way of where it was put.
    let x = to.number(&format!("data get entity {name} Pos[0]"));
    assert!(
        x.is_some_and(|x| (x - (f64::from(X) + 0.5)).abs() <= 3.6),
        "{name} is off his walk, at x {x:?}"
    );

    // Walk and chat for a while on the new node.
    sleep(secs(2));
    let said = format!("now on node {}", to.id);
    player.chat(&said);
    sleep(secs(5));
    let state = player.state();
    assert!(state.ended.is_none(), "{name}'s connection ended: {:?}", state.ended);
    assert_eq!(state.teleports_since(started), Vec::<[f64; 3]>::new(), "{name}'s client was pulled back");
    assert_eq!(state.anomalies.counts(), before.counts(), "{name}'s client saw something out of place: {:?}", state.anomalies);
    assert!(!state.chunks.is_empty() && !state.entities.is_empty(), "{name}'s client lost the world");
    assert!(state.heard(&[&said], started), "{name}'s chat did not go through after the move");
    drop(state);

    let state = observer.state();
    assert!(state.heard(&[&said], started), "{} did not hear {name} after the move", observer.name);
    assert_eq!(state.sees_player(player.uuid), shown_as, "{} was shown {name} anew", observer.name);
    assert_eq!(state.anomalies.counts(), observer_before.counts(), "{}'s client saw something out of place: {:?}", observer.name, state.anomalies);
}

pub fn a_walking_player_moves_between_nodes_unawares(cluster: &Cluster) {
    let walker = cluster.join("HandWalker");
    let observer = cluster.join("HandObserver");
    let (home, other) = (cluster.home_of("HandWalker").unwrap(), cluster.home_of("HandObserver").unwrap());
    assert_ne!(home.id, other.id, "both players were placed on {home:?}");
    let _unload = Defer(|| {
        home.cmd(&format!("forceload remove {X} {Z}"));
    });

    platform(&home);
    home.cmds(&[
        "clear HandWalker",
        "give HandWalker minecraft:diamond 3",
        "advancement grant HandWalker only minecraft:story/root",
        &format!("tp HandWalker {X}.5 {Y} {Z}.5"),
    ]);
    other.cmd(&format!("tp HandObserver {}.5 {Y} {}.5", X + 2, Z + 5));
    walker.wait_for("the walker to land on the platform", secs(15), |s| match s.position {
        Some([_, y, _]) if y == f64::from(Y) => Ok(()),
        other => Err(format!("at {other:?}")),
    });
    walker.walk(true);
    observer.wait_for("the observer to be shown the walker", secs(15), |s| {
        s.sees_player(walker.uuid).ok_or_else(|| "not shown".to_owned())
    });
    // Settling in, then pacing back and forth, with the setup's chunks and
    // entities all sent.
    sleep(secs(8));

    move_player(&walker, &observer, &home, &other);
    move_player(&walker, &observer, &other, &home);
}

/// lodestar moves a player to the node that simulates where they are, on its
/// own, and then leaves both players where they are.
pub fn lodestar_moves_players_to_where_they_are_simulated() {
    let cluster = Cluster::start("auto-handoff", Options { auto_handoff: true, ..Options::default() });
    let alex = cluster.join("Alex");
    let steve = cluster.join("Steve");
    let (own, other) = (cluster.home_of("Alex").unwrap(), cluster.home_of("Steve").unwrap());
    assert_ne!(own.id, other.id, "both players were placed on {own:?}");

    platform(&own);
    own.cmd(&format!("tp Alex {}.5 {Y} {Z}.5", X - 4));
    other.cmd(&format!("tp Steve {}.5 {Y} {}.5", X + 4, Z + 4));
    steve.wait_for("Steve to land on the platform", secs(15), |s| match s.position {
        Some([x, y, _]) if y == f64::from(Y) && (x - f64::from(X)).abs() < 8.0 => Ok(()),
        other => Err(format!("at {other:?}")),
    });
    sleep(secs(2));
    let settled = Instant::now();
    let before = steve.state().anomalies.clone();

    // Players are not moved in their first 30 seconds on a node.
    wait_until(&format!("Steve to be moved to {own:?}"), secs(90), || own.has_player("Steve"));
    assert!(own.has_player("Alex"), "Alex was moved off the node that simulates where he is");
    sleep(secs(5));
    {
        let state = steve.state();
        assert!(state.ended.is_none(), "Steve's connection ended: {:?}", state.ended);
        assert!(state.teleports_since(settled).is_empty(), "Steve's client was pulled back");
        assert_eq!(state.anomalies.counts(), before.counts(), "Steve's client saw something out of place: {:?}", state.anomalies);
    }

    // Two players on one node and none on the other is even enough: nobody
    // moves again.
    sleep(secs(20));
    assert!(own.has_player("Steve") && own.has_player("Alex"), "a player was moved again");
    assert!(alex.connected() && steve.connected(), "a client was disconnected");
}
