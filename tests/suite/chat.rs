//! Chat, and what the game announces to every player, reaches the players of
//! every node.

use std::thread::sleep;
use std::time::Instant;

use lodestar_e2e::client::Event;
use lodestar_e2e::{Client, Cluster, Defer, secs};

fn hears(client: &Client, what: &str, words: &[&str], since: Instant) {
    client.wait_for(what, secs(15), |s| {
        if s.heard(words, since) { Ok(()) } else { Err(format!("nothing with {words:?}")) }
    });
}

pub fn chat_and_announcements_reach_every_node(cluster: &Cluster) {
    let start = Instant::now();
    let alex = cluster.join("ChatAlex");
    let steve = cluster.join("ChatSteve");
    let (at_alex, at_steve) = (cluster.home_of("ChatAlex").unwrap(), cluster.home_of("ChatSteve").unwrap());
    assert_ne!(at_alex.id, at_steve.id, "both players were placed on {at_alex:?}");
    let _restore = Defer(|| {
        at_alex.cmds(&["team remove e2e_chat", "team remove e2e_quiet"]);
    });

    hears(&alex, "Alex to hear that Steve joined", &["multiplayer.player.joined", "ChatSteve"], start);

    alex.chat("hello from alex");
    hears(&steve, "Steve to hear Alex", &["hello from alex"], start);
    steve.chat("hello from steve");
    hears(&alex, "Alex to hear Steve", &["hello from steve"], start);
    let log = at_steve.log();
    assert!(
        log.lines().any(|l| l.contains("[Node #") && l.contains("<ChatAlex> hello from alex")),
        "Steve's node did not log Alex's chat with the node it came from"
    );

    at_alex.cmd("say the cluster says hello");
    hears(&steve, "Steve to hear what Alex's node says", &["the cluster says hello"], start);
    at_steve.cmd("say and hello back");
    hears(&alex, "Alex to hear what Steve's node says", &["and hello back"], start);

    // A team's messages reach its players on every node, and nobody else.
    at_alex.cmd("team add e2e_chat");
    sleep(secs(1));
    at_alex.cmd("team join e2e_chat ChatAlex");
    sleep(secs(1));
    let sent = Instant::now();
    at_alex.cmd("execute as ChatAlex run teammsg a secret for the team");
    hears(&alex, "Alex to hear his own team message", &["a secret for the team"], sent);
    sleep(secs(2));
    assert!(!steve.state().heard(&["a secret for the team"], sent), "Steve, on no team, heard it");
    at_steve.cmd("team join e2e_chat ChatSteve");
    sleep(secs(1));
    at_alex.cmd("execute as ChatAlex run teammsg welcome to the team");
    hears(&steve, "Steve, on the team now, to hear it", &["welcome to the team"], sent);

    // Deaths are announced on every node, to whom the dead player's team lets hear them.
    at_steve.cmds(&[
        "team leave ChatSteve",
        "team add e2e_quiet",
        "team modify e2e_quiet deathMessageVisibility hideForOtherTeams",
        "team join e2e_quiet ChatSteve",
    ]);
    sleep(secs(1));
    let killed = Instant::now();
    at_steve.cmd("kill ChatSteve");
    sleep(secs(3));
    assert!(
        !alex.state().heard(&["death.", "ChatSteve"], killed),
        "Alex, on another team, heard that Steve died"
    );
    at_alex.cmd("team leave ChatAlex");
    sleep(secs(1));
    at_alex.cmd("kill ChatAlex");
    hears(&steve, "Steve to hear that Alex died", &["death.", "ChatAlex"], killed);
    for client in [&alex, &steve] {
        client.wait_for("the dead to respawn", secs(15), |s| {
            if s.events_since(killed).any(|e| matches!(e, Event::Respawned)) {
                Ok(())
            } else {
                Err("not yet".into())
            }
        });
    }

    let left = Instant::now();
    steve.quit();
    hears(&alex, "Alex to hear that Steve left", &["multiplayer.player.left", "ChatSteve"], left);
}
