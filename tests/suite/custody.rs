//! Players use what another node simulates, through custody, and nothing is
//! duplicated. Players are driven with `/lodecore act`, which calls the code
//! their clicks would reach.

use std::thread::sleep;

use lodestar_e2e::cluster::item_count;
use lodestar_e2e::{Cluster, Defer, Node, secs, stays, wait_for, wait_until};

const X: i32 = 3200;
const Y: i32 = 150;
const Z: i32 = 3200;

/// How many of an item a player homed on `node` has.
fn holding(node: &Node, player: &str, item: &str) -> i64 {
    let reply = node.cmd(&format!("clear {player} minecraft:{item} 0"));
    lodestar_e2e::cluster::int_after(&reply, "Found ").unwrap_or(0)
}

/// How many of an item a container holds, as `node` has it.
fn in_container(node: &Node, pos: &str, item: &str) -> u32 {
    item_count(&node.cmd(&format!("data get block {pos} Items")), item)
}

pub fn players_use_what_another_node_simulates_without_duplicates(cluster: &Cluster) {
    let alex = cluster.join("CusAlex");
    let steve = cluster.join("CusSteve");
    let (own, rep) = (cluster.home_of("CusAlex").unwrap(), cluster.home_of("CusSteve").unwrap());
    assert_ne!(own.id, rep.id, "both players were placed on {own:?}");
    let area = format!("x={X},y={Y},z={Z},distance=..30");
    let _restore = Defer(|| {
        for node in [&own, &rep] {
            node.cmd(&format!("kill @e[type=item,{area}]"));
            node.cmd("kill @e[tag=e2e_custody]");
        }
        own.cmd(&format!("forceload remove {X} {Z}"));
    });

    crate::blocks::own(&own, X, Z);
    own.cmds(&[
        &format!("fill {} {} {} {} {} {} stone", X - 6, Y - 1, Z - 6, X + 9, Y - 1, Z + 9),
        &format!("fill {} {Y} {} {} {} {} air", X - 6, Z - 6, X + 9, Y + 4, Z + 9),
        "clear CusAlex",
        &format!("tp CusAlex {} {Y} {}", X - 5, Z - 5),
    ]);
    rep.cmds(&["clear CusSteve", &format!("tp CusSteve {} {Y} {}", X + 8, Z + 8)]);
    sleep(secs(4));

    // Block entities replicate.
    let chest = format!("{X} {Y} {Z}");
    own.cmd(&format!("setblock {chest} chest"));
    own.cmd(&format!("item replace block {chest} container.0 with diamond 5"));
    wait_until("the chest's diamonds to be seen on Steve's node", secs(8), || {
        in_container(&rep, &chest, "diamond") == 5
    });
    let sign = format!("{} {Y} {Z}", X + 2);
    own.cmd(&format!("setblock {sign} oak_sign"));
    own.cmd(&format!(
        "data merge block {sign} {{front_text:{{messages:[\"hello\",\"\",\"\",\"\"]}}}}"
    ));
    wait_until("the sign's text to be seen on Steve's node", secs(8), || {
        rep.cmd(&format!("data get block {sign} front_text")).contains("hello")
    });

    // Steve takes what is in a chest Alex's node simulates.
    rep.cmd(&format!("tp CusSteve {} {Y} {}", X + 1, Z + 1));
    sleep(secs(1));
    rep.cmd(&format!("lodecore act CusSteve use {chest}"));
    wait_until("Steve's node to get custody and open the chest", secs(8), || {
        rep.cmd("lodecore act CusSteve take 0").contains("shift-clicked 5")
    });
    wait_until("Steve to have the diamonds", secs(8), || holding(&rep, "CusSteve", "diamond") == 5);
    wait_until("the owner's chest to be empty", secs(8), || in_container(&own, &chest, "diamond") == 0);

    // While Steve has it open, nobody else can use it.
    own.cmd(&format!("tp CusAlex {} {Y} {}", X - 1, Z - 1));
    sleep(secs(1));
    own.cmd(&format!("lodecore act CusAlex use {chest}"));
    sleep(secs(1));
    let reply = own.cmd("lodecore act CusAlex take 0");
    assert!(reply.contains("no menu open"), "Alex opened the chest Steve has: {reply:?}");
    own.cmd(&format!("item replace block {chest} container.1 with iron_ingot 3"));
    sleep(secs(1));
    assert_eq!(in_container(&rep, &chest, "iron_ingot"), 0, "the owner slipped iron into the chest");
    rep.cmd("lodecore act CusSteve close");
    sleep(secs(3));
    assert_eq!(
        (
            holding(&rep, "CusSteve", "diamond"),
            in_container(&own, &chest, "diamond"),
            in_container(&rep, &chest, "diamond")
        ),
        (5, 0, 0),
        "diamonds held by Steve, in the owner's chest, in Steve's node's chest, once handed back"
    );

    // The owner's hoppers leave a chest alone while another node has it.
    own.cmd(&format!("item replace block {chest} container.1 with iron_ingot 3"));
    wait_until("the iron to be in the chest on both nodes", secs(8), || {
        in_container(&rep, &chest, "iron_ingot") == 3
    });
    rep.cmd(&format!("lodecore act CusSteve use {chest}"));
    wait_until("Steve to have it open", secs(8), || {
        rep.cmd("lodecore act CusSteve take 0").contains("shift-clicked 0")
    });
    own.cmd(&format!("setblock {X} {} {Z} hopper", Y - 1));
    stays("the owner's hopper to take nothing meanwhile", secs(3), || {
        match in_container(&own, &chest, "iron_ingot") {
            3 => Ok(()),
            n => Err(format!("{n} iron left")),
        }
    });
    rep.cmd("lodecore act CusSteve close");
    wait_until("the hopper to take the iron once the chest is handed back", secs(10), || {
        in_container(&own, &format!("{X} {} {Z}", Y - 1), "iron_ingot") > 0
    });
    own.cmd(&format!("setblock {X} {} {Z} stone", Y - 1));

    // Two players on different nodes step on the same item, three times.
    for attempt in 0..3 {
        let (sx, sz) = (X + 4, Z + 4 + attempt);
        own.cmd(&format!(
            "summon item {sx} {Y} {sz} {{Item:{{id:\"gold_ingot\",count:1}},PickupDelay:40s}}"
        ));
        wait_until(&format!("item {} to be on Steve's node too", attempt + 1), secs(8), || {
            rep.passes(&format!("entity @e[type=item,x={sx},y={Y},z={sz},distance=..1]"))
        });
        own.cmd(&format!("tp CusAlex {sx} {Y} {sz}"));
        rep.cmd(&format!("tp CusSteve {sx} {Y} {sz}"));
        sleep(secs(4));
    }
    let (alex_gold, steve_gold) = (holding(&own, "CusAlex", "gold_ingot"), holding(&rep, "CusSteve", "gold_ingot"));
    assert_eq!(alex_gold + steve_gold, 3, "Alex picked up {alex_gold}, Steve {steve_gold}");
    for node in [&own, &rep] {
        assert!(
            !node.passes(&format!("entity @e[type=item,{area},nbt={{Item:{{id:\"minecraft:gold_ingot\"}}}}]")),
            "gold is left lying around on {node:?}"
        );
    }

    // Steve shears a sheep Alex's node simulates.
    rep.cmd("item replace entity CusSteve weapon.mainhand with shears");
    own.cmd(&format!(
        "summon sheep {} {Y} {Z} {{NoAI:1b,Tags:[\"e2e_custody\"]}}",
        X + 6
    ));
    rep.cmd(&format!("tp CusSteve {} {Y} {Z}", X + 5));
    wait_until("the sheep to be on Steve's node", secs(8), || rep.passes("entity @e[tag=e2e_custody]"));
    sleep(secs(1));
    rep.cmd("lodecore act CusSteve interact @e[tag=e2e_custody,limit=1]");
    wait_until("the sheep to be shorn on the owner", secs(8), || {
        own.cmd("data get entity @e[tag=e2e_custody,limit=1] Sheared").contains("1b")
    });
    wait_until("its wool to lie on the owner's ground", secs(8), || {
        own.passes(&format!("entity @e[type=item,{area},nbt={{Item:{{id:\"minecraft:white_wool\"}}}}]"))
    });
    let shears = rep.cmd("data get entity CusSteve SelectedItem");
    assert!(shears.contains("damage"), "Steve's shears did not wear: {shears:?}");

    // Steve breaks a chest Alex's node simulates; what was in it spills once.
    let box_pos = format!("{} {Y} {}", X + 2, Z + 4);
    own.cmd(&format!("setblock {box_pos} chest"));
    own.cmd(&format!("item replace block {box_pos} container.0 with apple 4"));
    wait_until("the apples to be on Steve's node", secs(8), || in_container(&rep, &box_pos, "apple") == 4);
    own.cmd(&format!("tp CusAlex {} {Y} {}", X - 5, Z - 5));
    rep.cmd(&format!("tp CusSteve {} {Y} {}", X + 2, Z + 6));
    sleep(secs(1));
    rep.cmd(&format!("lodecore act CusSteve break {box_pos}"));
    wait_until("the chest to be gone on the owner", secs(8), || {
        own.passes(&format!("block {box_pos} air"))
    });
    sleep(secs(2));
    own.cmd(&format!("tp CusAlex {} {Y} {}", X + 2, Z + 4));
    let apples = wait_for("the four apples to be picked up", secs(10), || {
        let apples = holding(&own, "CusAlex", "apple") + holding(&rep, "CusSteve", "apple");
        if apples >= 4 { Ok(apples) } else { Err(format!("{apples} picked up")) }
    });
    sleep(secs(2));
    let apples = apples.max(holding(&own, "CusAlex", "apple") + holding(&rep, "CusSteve", "apple"));
    assert_eq!(apples, 4, "the chest's four apples spilled as {apples}");
    drop((alex, steve));
}
