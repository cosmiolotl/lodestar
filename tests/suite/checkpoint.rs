//! A connected player's progress is committed with the world at checkpoints,
//! as it changes: advancements granted and revoked, statistics, and what a
//! resource reload or a bulk save does not get to undo.

use std::io::Read;
use std::time::{Duration, Instant};

use flate2::read::GzDecoder;
use lodestar_e2e::{Cluster, Options, secs};

use crate::recovery::checkpoint;

/// A JSON string field of the committed player, found in its NBT by name.
fn committed_json(cluster: &Cluster, name: &str) -> serde_json::Value {
    let db = rusqlite::Connection::open_with_flags(
        cluster.dir.join("data/world.sqlite3"),
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY,
    )
    .expect("opening lodestar's database");
    db.busy_timeout(Duration::from_secs(10)).unwrap();
    let rows: Vec<Vec<u8>> = db
        .prepare("SELECT data FROM players")
        .unwrap()
        .query_map([], |row| row.get(0))
        .unwrap()
        .collect::<Result<_, _>>()
        .unwrap();
    assert_eq!(rows.len(), 1, "expected one committed player");
    let mut nbt = Vec::new();
    GzDecoder::new(&rows[0][..]).read_to_end(&mut nbt).expect("player data is gzipped NBT");
    // A string tag: its type, the length of its name, the name, the length of the value.
    let mut marker = vec![8];
    marker.extend_from_slice(&(name.len() as u16).to_be_bytes());
    marker.extend_from_slice(name.as_bytes());
    let at = nbt
        .windows(marker.len())
        .position(|w| w == marker)
        .unwrap_or_else(|| panic!("no {name} in the committed player"))
        + marker.len();
    let len = u16::from_be_bytes([nbt[at], nbt[at + 1]]) as usize;
    serde_json::from_slice(&nbt[at + 2..at + 2 + len]).expect("the field holds JSON")
}

fn play_time(cluster: &Cluster) -> i64 {
    committed_json(cluster, "LodecoreStats")["stats"]["minecraft:custom"]["minecraft:play_time"]
        .as_i64()
        .unwrap_or(0)
}

/// Waits for checkpoints until the committed advancements have, or lack, one.
fn committed_with(cluster: &Cluster, advancement: &str, present: bool) -> serde_json::Value {
    // The revision under way when the command ran may predate it.
    for _ in 0..3 {
        checkpoint(cluster);
        let advancements = committed_json(cluster, "LodecoreAdvancements");
        if advancements.get(advancement).is_some() == present {
            return advancements;
        }
    }
    panic!("the committed advancements never had {advancement} = {present}");
}

pub fn a_connected_players_progress_is_committed() {
    const ADVANCEMENT: &str = "minecraft:nether/uneasy_alliance";
    let cluster = Cluster::start("player-checkpoints", Options { nodes: 1, ..Options::default() });
    let worker = &cluster.live_nodes()[0];
    let player = cluster.join("Persist");
    worker.cmd("gamemode creative Persist");
    checkpoint(&cluster);

    worker.cmd(&format!("advancement grant Persist only {ADVANCEMENT}"));
    let granted = committed_with(&cluster, ADVANCEMENT, true);
    checkpoint(&cluster);
    assert_eq!(committed_json(&cluster, "LodecoreAdvancements"), granted, "unchanged progress changed");
    let first_play_time = play_time(&cluster);

    worker.cmd(&format!("advancement revoke Persist only {ADVANCEMENT}"));
    committed_with(&cluster, ADVANCEMENT, false);
    worker.cmd(&format!("advancement grant Persist only {ADVANCEMENT}"));
    committed_with(&cluster, ADVANCEMENT, true);

    // A resource reload must not throw away live progress.
    let reloaded = Instant::now();
    worker.cmd("reload");
    lodestar_e2e::wait_until("the reload to finish", secs(60), || {
        worker.log().rsplit("Reloading!").next().is_some_and(|after| after.contains("Loaded "))
    });
    for _ in 0..3 {
        checkpoint(&cluster);
    }
    assert!(
        committed_json(&cluster, "LodecoreAdvancements").get(ADVANCEMENT).is_some(),
        "the reload {:.0?} ago threw away live progress",
        reloaded.elapsed()
    );
    assert!(
        worker.passes(&format!("entity @a[name=Persist,advancements={{{ADVANCEMENT}=true}}]")),
        "the player lost the advancement"
    );

    worker.cmd("save-all flush");
    checkpoint(&cluster);
    let play_time = play_time(&cluster);
    assert!(play_time > first_play_time, "play time stayed at {first_play_time} across checkpoints");
    assert!(player.connected(), "the player was disconnected by a checkpoint or the reload");
    assert!(worker.stop().success(), "the worker did not stop cleanly");
}
