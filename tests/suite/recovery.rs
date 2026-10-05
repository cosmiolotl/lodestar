//! lodestar keeps the world and its players. A worker that starts empty gets
//! everything back from it, after lodestar restarts and after a worker crashes.

use std::thread::sleep;
use std::time::Duration;

use lodestar_e2e::cluster::item_count;
use lodestar_e2e::{Cluster, Node, Options, secs, wait_for, wait_until};
use rusqlite::{Connection, OpenFlags};

fn database(cluster: &Cluster) -> Connection {
    let path = cluster.dir.join("data/world.sqlite3");
    let db = Connection::open_with_flags(&path, OpenFlags::SQLITE_OPEN_READ_ONLY)
        .unwrap_or_else(|e| panic!("opening {}: {e}", path.display()));
    db.busy_timeout(Duration::from_secs(10)).unwrap();
    db
}

/// The last world revision lodestar committed.
pub fn revision(cluster: &Cluster) -> i64 {
    database(cluster)
        .query_row("SELECT value FROM metadata WHERE key='revision'", [], |row| row.get(0))
        .expect("reading the committed revision")
}

/// Waits for lodestar to commit the next world revision.
pub fn checkpoint(cluster: &Cluster) {
    let before = revision(cluster);
    wait_until("the next world checkpoint", secs(60), || revision(cluster) > before);
}

fn integrity(cluster: &Cluster) -> String {
    database(cluster)
        .query_row("PRAGMA integrity_check", [], |row| row.get(0))
        .expect("checking the database")
}

fn play_time(node: &Node) -> i64 {
    let dir = node.dir.join("world/players/stats");
    let file = std::fs::read_dir(&dir)
        .unwrap_or_else(|e| panic!("reading {}: {e}", dir.display()))
        .filter_map(Result::ok)
        .find(|e| e.path().extension().is_some_and(|x| x == "json"))
        .unwrap_or_else(|| panic!("{node:?} saved no statistics"));
    let stats: serde_json::Value = serde_json::from_slice(&std::fs::read(file.path()).unwrap()).unwrap();
    stats["stats"]["minecraft:custom"]["minecraft:play_time"].as_i64().unwrap_or(0)
}

fn expect_answer(node: &Node, command: &str, expected: &str) {
    wait_for(&format!("`{command}` to answer with {expected:?}"), secs(15), || {
        let reply = node.cmd(command);
        if reply.contains(expected) { Ok(()) } else { Err(reply) }
    });
}

pub fn an_empty_worker_recovers_everything_after_lodestar_restarts() {
    let cluster = Cluster::new("restart", Options::default());
    cluster.start_star();
    let first = cluster.add_node("original");
    cluster.start_proxy();
    let player = cluster.join("Persist");
    sleep(secs(2));
    first.cmds(&[
        "gamemode creative Persist",
        "item replace entity Persist hotbar.0 with minecraft:diamond 11",
        "item replace entity Persist enderchest.0 with minecraft:gold_ingot 7",
        "experience set Persist 5 levels",
        "advancement grant Persist only minecraft:story/root",
        "forceload add 320 -640",
        "setblock 320 200 -640 minecraft:diamond_block",
        "setblock 321 200 -640 minecraft:chest",
        "item replace block 321 200 -640 container.0 with minecraft:emerald 23",
        "summon minecraft:pig 323 201 -638 {PersistenceRequired:1b,NoAI:1b,NoGravity:1b,Tags:[\"central_pig\"]}",
        "scoreboard objectives add durable dummy",
        "scoreboard players set Saved durable 73",
        "data modify storage lodecore:durable marker set value \"central\"",
        "gamerule keep_inventory true",
        "save-all flush",
    ]);
    sleep(secs(3));

    // Another worker sees the world as the first one left it.
    let observer = cluster.add_node("observer");
    observer.cmd("forceload add 320 -640");
    wait_until("the observer to see the diamond block", secs(15), || {
        observer.has_block(320, 200, -640, "minecraft:diamond_block")
    });
    expect_answer(&observer, "data get block 321 200 -640 Items", "emerald");
    assert!(observer.stop().success(), "the observer did not stop cleanly");

    player.quit();
    sleep(secs(2));
    assert!(first.stop().success(), "the original worker did not stop cleanly");
    let first_play_time = play_time(&first);
    assert!(first_play_time > 0, "the original worker counted no play time");

    // Everything goes, but lodestar's data.
    cluster.stop_proxy();
    cluster.kill_star();
    cluster.start_star();
    let replacement = cluster.add_node("empty-replacement");
    cluster.start_proxy();
    let player = cluster.join("Persist");
    replacement.cmd("forceload add 320 -640");
    for (command, expected) in [
        ("data get entity Persist Inventory", "diamond"),
        ("data get entity Persist Inventory", "11"),
        ("data get entity Persist EnderItems", "gold_ingot"),
        ("data get entity Persist EnderItems", "7"),
        ("experience query Persist levels", "has 5 experience levels"),
        ("execute if entity @a[name=Persist,advancements={minecraft:story/root=true}]", "Test passed"),
        ("execute if block 320 200 -640 minecraft:diamond_block", "Test passed"),
        ("data get block 321 200 -640 Items", "emerald"),
        ("data get block 321 200 -640 Items", "23"),
        ("execute if entity @e[tag=central_pig]", "Test passed"),
        ("scoreboard players get Saved durable", "73"),
        ("data get storage lodecore:durable marker", "central"),
        ("gamerule keep_inventory", "true"),
    ] {
        expect_answer(&replacement, command, expected);
    }
    player.quit();
    sleep(secs(2));
    assert!(replacement.stop().success(), "the replacement did not stop cleanly");
    let recovered = play_time(&replacement);
    assert!(recovered >= first_play_time, "play time went from {first_play_time} to {recovered}");
}

/// A worker that holds a chest from another worker's region crashes while its
/// player has the chest's items on the cursor: once the cluster recovers, the
/// items are in exactly one place.
pub fn a_crashed_worker_neither_loses_nor_duplicates_what_it_held() {
    let cluster = Cluster::new("crash-custody", Options::default());
    cluster.start_star();
    let owner = cluster.add_node_with("owner", 1);
    cluster.start_proxy();
    // Fills the owner, so that the next player goes to the other worker.
    let anchor = cluster.join("Anchor");
    let custodian = cluster.add_node("custodian");
    let player = cluster.join("Persist");
    assert!(custodian.has_player("Persist"), "Persist did not land on the custodian");

    custodian.cmds(&[
        "gamemode creative Persist",
        "item replace entity Persist hotbar.0 with minecraft:diamond 11",
        // Into the crafting grid, which a player's saved inventory leaves out.
        "lodecore act Persist click 36",
        "lodecore act Persist click 1",
    ]);
    checkpoint(&cluster);

    owner.cmds(&[
        "forceload add 320 -640",
        "fill 318 199 -642 326 199 -634 minecraft:stone",
        "setblock 321 200 -640 minecraft:chest",
        "item replace block 321 200 -640 container.0 with minecraft:emerald 23",
        "summon minecraft:item 324 201 -637 {NoGravity:1b,PickupDelay:32767s,Item:{id:\"minecraft:gold_ingot\",count:7},Tags:[\"durable_drop\"]}",
    ]);
    checkpoint(&cluster);
    custodian.cmd("teleport Persist 322 200 -639");
    sleep(secs(1));
    custodian.cmd("lodecore act Persist use 321 200 -640");
    wait_for("the custodian to hold the chest", secs(10), || {
        let holder = custodian.cmd("lodecore holder 321 200 -640");
        if holder.contains(&format!("this node (#{})", custodian.id)) { Ok(()) } else { Err(holder) }
    });
    checkpoint(&cluster);
    let reply = custodian.cmd("lodecore act Persist click 0");
    assert!(reply.contains("cursor: 23 Emerald"), "the emeralds are not on the cursor: {reply:?}");

    // No goodbye, no save, no shutdown.
    custodian.kill();
    let star = cluster.wait_star_exit(secs(30)).expect("lodestar fences the cluster when a worker is lost");
    assert!(!star.success(), "lodestar exited cleanly after losing a worker");
    owner
        .wait_exit(secs(120))
        .expect("the owner stops once lodestar is gone");
    drop((player, anchor));
    cluster.stop_proxy();
    assert_eq!(integrity(&cluster), "ok");

    cluster.start_star();
    let recovered = cluster.add_node("empty-recovery");
    cluster.start_proxy();
    let _player = cluster.join("Persist");
    recovered.cmd("forceload add 320 -640");
    let (inventory, chest) = wait_for("the chest's emeralds to be somewhere", secs(15), || {
        let inventory = recovered.cmd("data get entity Persist Inventory");
        let chest = recovered.cmd("data get block 321 200 -640 Items");
        if item_count(&inventory, "emerald") + item_count(&chest, "emerald") > 0 {
            Ok((inventory, chest))
        } else {
            Err(format!("inventory {inventory:?}, chest {chest:?}"))
        }
    });
    assert_eq!(
        item_count(&inventory, "emerald") + item_count(&chest, "emerald"),
        23,
        "emeralds duplicated or lost: inventory {inventory:?}, chest {chest:?}"
    );
    assert_eq!(item_count(&inventory, "diamond"), 11, "the crafting grid's diamonds were lost: {inventory:?}");
    expect_answer(&recovered, "data get entity @e[tag=durable_drop,limit=1] Item", "gold_ingot");
    let drop = recovered.cmd("data get entity @e[tag=durable_drop,limit=1] Item");
    assert!(drop.contains("count: 7"), "the dropped gold is not 7 ingots: {drop:?}");
    assert!(recovered.stop().success(), "the recovered worker did not stop cleanly");
}

/// Changes made since the last checkpoint that a checkpoint then commits
/// survive a crash: inventories of entities that do not tick, an entity moved
/// to another section, one removed, and a block.
pub fn checkpointed_changes_survive_a_crash() {
    let cluster = Cluster::new("crash-dirty", Options { nodes: 1, ..Options::default() });
    cluster.start_star();
    let worker = cluster.add_node("worker");
    cluster.start_proxy();
    let player = cluster.join("Persist");
    worker.cmds(&[
        "forceload add 320 -640 351 -625",
        "fill 320 200 -640 351 200 -625 minecraft:stone",
        "gamemode creative Persist",
        "tp Persist 325.5 201 -635.5",
    ]);
    sleep(secs(3));
    worker.cmds(&[
        "summon minecraft:chest_minecart 321.5 201 -639.5 {NoGravity:1b,Tags:[\"dirty_cart\"]}",
        "summon minecraft:donkey 322.5 201 -639.5 {NoAI:1b,NoGravity:1b,Tame:1b,ChestedHorse:1b,Tags:[\"dirty_donkey\"]}",
        "summon minecraft:pig 323.5 201 -639.5 {NoAI:1b,NoGravity:1b,Tags:[\"removed_pig\"]}",
    ]);
    for tag in ["dirty_cart", "dirty_donkey", "removed_pig"] {
        wait_until(&format!("the {tag} to exist"), secs(30), || worker.passes(&format!("entity @e[tag={tag}]")));
    }
    checkpoint(&cluster);

    worker.cmd("lodecore freeze");
    for (command, expected) in [
        ("tp @e[tag=dirty_cart,limit=1] 337.5 201 -639.5", "Teleported"),
        ("item replace entity @e[tag=dirty_cart,limit=1] container.0 with minecraft:diamond 11", "Replaced"),
        ("item replace entity @e[tag=dirty_donkey,limit=1] horse.0 with minecraft:emerald 7", "Replaced"),
        ("kill @e[tag=removed_pig]", "Killed"),
    ] {
        let reply = worker.cmd(command);
        assert!(reply.contains(expected), "`{command}` answered {reply:?}");
    }
    worker.cmd("setblock 320 200 -640 minecraft:gold_block");
    // The next commit may already have been under way when the changes came.
    checkpoint(&cluster);
    checkpoint(&cluster);

    worker.kill();
    let star = cluster.wait_star_exit(secs(30)).expect("lodestar fences the cluster when a worker is lost");
    assert!(!star.success(), "lodestar exited cleanly after losing a worker");
    drop(player);
    cluster.stop_proxy();

    cluster.start_star();
    let recovered = cluster.add_node("empty-recovery");
    cluster.start_proxy();
    let _player = cluster.join("Persist");
    recovered.cmd("forceload add 320 -640 351 -625");
    wait_until("the moved minecart to be back", secs(30), || recovered.passes("entity @e[tag=dirty_cart]"));
    expect_answer(&recovered, "data get entity @e[tag=dirty_cart,limit=1] Items", "minecraft:diamond");
    expect_answer(&recovered, "data get entity @e[tag=dirty_cart,limit=1] Pos", "337.5d");
    expect_answer(&recovered, "data get entity @e[tag=dirty_donkey,limit=1] Items", "minecraft:emerald");
    assert!(!recovered.passes("entity @e[tag=removed_pig]"), "the killed pig came back");
    assert!(recovered.has_block(320, 200, -640, "minecraft:gold_block"), "the gold block was lost");
    assert!(recovered.stop().success(), "the recovered worker did not stop cleanly");
}
