//! Players log in through the proxy, which places them on the nodes.

use lodestar_e2e::client::{self, Client};
use lodestar_e2e::{Cluster, Options, secs, wait_for, wait_until};

pub fn players_are_spread_over_the_nodes(cluster: &Cluster) {
    let first = cluster.join("Spread1");
    let second = cluster.join("Spread2");
    let homes = [cluster.home_of("Spread1").unwrap(), cluster.home_of("Spread2").unwrap()];
    assert_ne!(homes[0].id, homes[1].id, "both players were placed on {:?}", homes[0]);

    for (client, home) in [(&first, &homes[0]), (&second, &homes[1])] {
        let id = client.state().entity_id.expect("the client was told its entity id");
        assert_eq!(
            home.entity_id(&client.name),
            Some(id),
            "{}'s client and its node disagree on its entity id",
            client.name
        );
        assert_eq!(client.state().anomalies.counts(), [0; 4], "{}", client.name);
    }

    first.quit();
    wait_until("Spread1 to leave", secs(15), || cluster.home_of("Spread1").is_none());
}

pub fn the_proxy_reports_the_whole_cluster(cluster: &Cluster) {
    let status = client::status(cluster.proxy_addr()).expect("the proxy answers a status request");
    assert_eq!(status["version"]["protocol"], 777, "{status}");
    assert_eq!(status["description"]["text"], "Lodestar E2E", "{status}");
    assert_eq!(status["players"]["max"], 40, "two nodes of 20 players: {status}");

    // Players of earlier tests may still be leaving, so the count is checked
    // against the nodes' player lists rather than against an earlier count.
    let counts = |what: &str, with_counted: bool| {
        wait_for(what, secs(15), || {
            let listed: Vec<String> = cluster.live_nodes().iter().flat_map(|n| n.players()).collect();
            let status = client::status(cluster.proxy_addr()).map_err(|e| e.to_string())?;
            let online = status["players"]["online"].as_u64().unwrap_or(u64::MAX);
            let counted = listed.iter().any(|p| p == "Counted");
            if online == listed.len() as u64 && counted == with_counted {
                Ok(())
            } else {
                Err(format!("{online} online, the nodes list {listed:?}"))
            }
        })
    };
    let player = cluster.join("Counted");
    counts("the proxy to count the new player", true);
    player.quit();
    counts("the proxy to stop counting the player who left", false);
}

pub fn nodes_turn_away_players_the_proxy_did_not_send(cluster: &Cluster) {
    for node in cluster.live_nodes() {
        let addr = ([127, 0, 0, 1], node.game_port).into();
        let reason = Client::refusal(addr, "Sneaky").expect("the node answers");
        assert!(
            reason.contains("This server can only be joined through its proxy."),
            "{node:?} refused with {reason:?}"
        );
        assert!(!node.has_player("Sneaky"));
    }
}

/// A player's data lives with lodestar, so it follows them to whichever node
/// they join next.
pub fn players_keep_their_things_on_another_node(cluster: &Cluster) {
    let player = cluster.join("Keeper");
    let first = cluster.home_of("Keeper").unwrap();
    first.cmds(&[
        "clear Keeper",
        "give Keeper minecraft:diamond 5",
        "item replace entity Keeper enderchest.3 with minecraft:gold_ingot 7",
        "experience set Keeper 9 levels",
        "advancement grant Keeper only minecraft:story/mine_stone",
    ]);
    player.quit();
    wait_until("Keeper to leave", secs(15), || cluster.home_of("Keeper").is_none());

    // The proxy places a player on the emptiest node: fill the one Keeper left.
    let filler = cluster.join("Filler");
    let filler_home = cluster.home_of("Filler").unwrap();
    if filler_home.id != first.id {
        filler_home.cmd(&format!("lodecore move Filler {}", first.id));
        wait_until("Filler to move to Keeper's old node", secs(30), || first.has_player("Filler"));
    }

    let player = cluster.join("Keeper");
    let second = cluster.home_of("Keeper").unwrap();
    assert_ne!(second.id, first.id, "Keeper came back to the node he left");
    for (command, expected) in [
        ("clear Keeper minecraft:diamond 0", "Found 5"),
        ("data get entity Keeper EnderItems", "gold_ingot"),
        ("experience query Keeper levels", "has 9 experience levels"),
        (
            "execute if entity @a[name=Keeper,advancements={minecraft:story/mine_stone=true}]",
            "Test passed",
        ),
    ] {
        let reply = second.cmd(command);
        assert!(reply.contains(expected), "`{command}` on {second:?} answered {reply:?}");
    }
    drop(player);
    drop(filler);
}

/// Programs without the cluster's token are turned away, and so are players
/// that no node can take, with a reason they are shown.
pub fn players_no_node_can_take_are_told_why() {
    let cluster = Cluster::new("turned-away", Options { nodes: 1, max_players: 1, ..Options::default() });
    cluster.start_star();
    cluster.start_proxy();
    let proxy = cluster.proxy_addr();
    let refused = |name: &str, reason: &str| {
        let shown = Client::refusal(proxy, name).unwrap_or_else(|e| panic!("{e:#}"));
        assert!(shown.contains(reason), "{name} was turned away with {shown:?}, not {reason:?}");
    };

    refused("Early", "No servers are online right now.");

    // A node cannot start without the world, which lodestar keeps from it.
    let line = cluster.refused_node("impostor", "not-the-token");
    assert!(line.contains("Lodestar rejected"), "the node gave up with {line:?}");
    let line = cluster.refused_proxy("not-the-token");
    assert!(line.contains("invalid token"), "the proxy was turned away with {line:?}");
    let turned_away = cluster.star_log().matches("invalid token").count();
    assert!(turned_away >= 2, "lodestar logged turning away {turned_away} programs, not the node and the proxy");
    refused("Early", "No servers are online right now.");

    let node = cluster.add_node("only");
    let first = cluster.join("First");
    refused("Second", "The server is full.");
    refused("First", "You are already connected.");
    assert!(first.connected(), "logging in again as First disconnected First");

    first.quit();
    wait_until("First to leave", secs(15), || !node.has_player("First"));
    let second = cluster.join("Second");
    assert!(second.connected());
}

/// The proxy hands players only to servers that check the identity it vouches
/// for. One that does not, such as a server without Lodecore, would let anyone
/// in under any name, so a node that sends players to one is not trusted.
pub fn players_are_only_sent_to_servers_that_check_who_they_are() {
    let cluster = Cluster::new("unchecked", Options { nodes: 1, ..Options::default() });
    cluster.start_star();
    cluster.start_proxy();
    let plain = cluster.start_plain_server("plain");
    cluster.add_node_sending_players_to("misdirecting", plain);
    let reason = Client::refusal(cluster.proxy_addr(), "Visitor").unwrap_or_else(|e| panic!("{e:#}"));
    assert!(reason.contains("Could not reach the server."), "Visitor was turned away with {reason:?}");
}
