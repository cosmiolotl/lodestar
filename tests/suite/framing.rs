//! The proxy passes packets through as the node framed them, whatever the
//! node's compression threshold. The shared cluster compresses at 256; these
//! nodes do not compress at all, or compress everything.

use std::time::Instant;

use lodestar_e2e::{Cluster, Options, secs};

fn players_play_with_compression(threshold: i32) {
    let options = Options { nodes: 1, compression: threshold, ..Options::default() };
    let cluster = Cluster::start(&format!("compression{threshold}"), options);
    let started = Instant::now();
    let walker = cluster.join("Framing");
    let viewers = [cluster.join("Viewer0"), cluster.join("Viewer1")];
    walker.walk(true);
    walker.chat("framed alright");

    walker.wait_for("chunks, the other players, and its own chat", secs(30), |s| {
        if !s.chunks.is_empty() && s.entities.len() >= 2 && s.heard(&["framed alright"], started) {
            Ok(())
        } else {
            Err(format!("{} chunks, {} entities", s.chunks.len(), s.entities.len()))
        }
    });
    for viewer in &viewers {
        viewer.wait_for("the walker's chat", secs(15), |s| {
            if s.heard(&["framed alright"], started) { Ok(()) } else { Err("not heard".into()) }
        });
    }
    for client in std::iter::once(&walker).chain(&viewers) {
        let state = client.state();
        assert!(state.ended.is_none(), "{} was disconnected: {:?}", client.name, state.ended);
        assert_eq!(state.anomalies.counts(), [0; 4], "{}: {:?}", client.name, state.anomalies);
    }
}

pub fn players_play_on_nodes_that_do_not_compress() {
    players_play_with_compression(-1);
}

pub fn players_play_on_nodes_that_compress_everything() {
    players_play_with_compression(0);
}
