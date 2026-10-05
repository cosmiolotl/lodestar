//! Lodestar's end-to-end tests. See `tests/README.md`.
//!
//! Most tests share one two-node cluster, started when the first of them
//! runs, and each works in a part of the world of its own, with players of
//! its own. Tests that restart or crash parts of a cluster, or need it set up
//! differently, start their own, after the shared one has been stopped.
//! Tests run one at a time.

mod blocks;
mod chat;
mod checkpoint;
mod clock;
mod custody;
mod events;
mod framing;
mod handoff;
mod login;
mod players;
mod recovery;
mod regions;
mod shared_data;
mod world_state;

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use libtest_mimic::{Arguments, Failed, Trial};
use lodestar_e2e::{Cluster, Options};

fn main() {
    let mut args = Arguments::from_args();
    args.test_threads = Some(1);

    // Shared tests first: the shared cluster stops for the first isolated one.
    let trials = vec![
        shared("login::players_are_spread_over_the_nodes", login::players_are_spread_over_the_nodes),
        shared("login::the_proxy_reports_the_whole_cluster", login::the_proxy_reports_the_whole_cluster),
        shared(
            "login::nodes_turn_away_players_the_proxy_did_not_send",
            login::nodes_turn_away_players_the_proxy_did_not_send,
        ),
        shared("clock::nodes_tick_in_lockstep", clock::nodes_tick_in_lockstep),
        shared("blocks::changes_reach_every_node", blocks::changes_reach_every_node),
        shared("blocks::only_the_owner_ticks_what_it_owns", blocks::only_the_owner_ticks_what_it_owns),
        shared(
            "blocks::updates_cross_between_chunks_of_different_nodes",
            blocks::updates_cross_between_chunks_of_different_nodes,
        ),
        shared(
            "blocks::a_player_builds_where_another_node_simulates",
            blocks::a_player_builds_where_another_node_simulates,
        ),
        shared(
            "regions::far_apart_areas_have_their_own_owners_and_merge",
            regions::far_apart_areas_have_their_own_owners_and_merge,
        ),
        shared(
            "players::players_on_different_nodes_see_and_hurt_each_other",
            players::players_on_different_nodes_see_and_hurt_each_other,
        ),
        shared(
            "custody::players_use_what_another_node_simulates_without_duplicates",
            custody::players_use_what_another_node_simulates_without_duplicates,
        ),
        shared(
            "world_state::every_node_has_the_same_world_state",
            world_state::every_node_has_the_same_world_state,
        ),
        shared(
            "shared_data::every_node_has_the_same_cluster_data",
            shared_data::every_node_has_the_same_cluster_data,
        ),
        shared("chat::chat_and_announcements_reach_every_node", chat::chat_and_announcements_reach_every_node),
        shared(
            "events::players_on_different_nodes_share_an_event",
            events::players_on_different_nodes_share_an_event,
        ),
        shared(
            "handoff::a_walking_player_moves_between_nodes_unawares",
            handoff::a_walking_player_moves_between_nodes_unawares,
        ),
        shared(
            "login::players_keep_their_things_on_another_node",
            login::players_keep_their_things_on_another_node,
        ),
        isolated(
            "handoff::lodestar_moves_players_to_where_they_are_simulated",
            handoff::lodestar_moves_players_to_where_they_are_simulated,
        ),
        isolated(
            "framing::players_play_on_nodes_that_do_not_compress",
            framing::players_play_on_nodes_that_do_not_compress,
        ),
        isolated(
            "framing::players_play_on_nodes_that_compress_everything",
            framing::players_play_on_nodes_that_compress_everything,
        ),
        isolated("login::players_no_node_can_take_are_told_why", login::players_no_node_can_take_are_told_why),
        isolated(
            "login::players_are_only_sent_to_servers_that_check_who_they_are",
            login::players_are_only_sent_to_servers_that_check_who_they_are,
        ),
        isolated(
            "regions::a_leaving_node_hands_its_area_to_a_node_that_has_it",
            regions::a_leaving_node_hands_its_area_to_a_node_that_has_it,
        ),
        isolated(
            "recovery::an_empty_worker_recovers_everything_after_lodestar_restarts",
            recovery::an_empty_worker_recovers_everything_after_lodestar_restarts,
        ),
        isolated(
            "recovery::a_crashed_worker_neither_loses_nor_duplicates_what_it_held",
            recovery::a_crashed_worker_neither_loses_nor_duplicates_what_it_held,
        ),
        isolated(
            "recovery::checkpointed_changes_survive_a_crash",
            recovery::checkpointed_changes_survive_a_crash,
        ),
        isolated(
            "checkpoint::a_connected_players_progress_is_committed",
            checkpoint::a_connected_players_progress_is_committed,
        ),
    ];

    let conclusion = libtest_mimic::run(&args, trials);
    stop_shared();
    report_times();
    conclusion.exit();
}

/// The cluster most tests share. A failure to start it fails each of them.
static SHARED: OnceLock<Result<Cluster, String>> = OnceLock::new();
static SHARED_STOPPED: Mutex<bool> = Mutex::new(false);
static TIMES: Mutex<Vec<(String, Duration, bool)>> = Mutex::new(Vec::new());

fn shared_cluster() -> &'static Cluster {
    assert!(
        !*SHARED_STOPPED.lock().unwrap(),
        "the shared cluster was stopped for an isolated test; shared tests must be listed first"
    );
    let started = SHARED.get_or_init(|| {
        catch_unwind(|| Cluster::start("shared", Options::default())).map_err(panic_message)
    });
    match started {
        Ok(cluster) => {
            for node in cluster.nodes() {
                if let Some(status) = node.exit_status() {
                    panic!("the shared cluster is down: {node:?} exited ({status})");
                }
            }
            cluster
        }
        Err(e) => panic!("the shared cluster did not start: {e}"),
    }
}

fn stop_shared() {
    *SHARED_STOPPED.lock().unwrap() = true;
    if let Some(Ok(cluster)) = SHARED.get() {
        cluster.shutdown();
    }
}

/// A test on the shared cluster.
fn shared(name: &'static str, test: fn(&Cluster)) -> Trial {
    Trial::test(name, move || timed(name, || test(shared_cluster()))).with_kind("shared")
}

/// A test that starts a cluster of its own.
fn isolated(name: &'static str, test: fn()) -> Trial {
    Trial::test(name, move || {
        // Two clusters at once would be too much for many machines.
        stop_shared();
        timed(name, test)
    })
    .with_kind("isolated")
}

fn timed(name: &str, test: impl FnOnce()) -> Result<(), Failed> {
    let started = Instant::now();
    let result = catch_unwind(AssertUnwindSafe(test));
    TIMES
        .lock()
        .unwrap()
        .push((name.to_owned(), started.elapsed(), result.is_ok()));
    result.map_err(|panic| Failed::from(panic_message(panic)))
}

fn panic_message(panic: Box<dyn std::any::Any + Send>) -> String {
    panic
        .downcast_ref::<String>()
        .cloned()
        .or_else(|| panic.downcast_ref::<&str>().map(|s| (*s).to_owned()))
        .unwrap_or_else(|| "the test panicked".to_owned())
}

fn report_times() {
    let times = TIMES.lock().unwrap();
    if times.is_empty() {
        return;
    }
    eprintln!("\ntimes:");
    for (name, took, passed) in times.iter() {
        let outcome = if *passed { "ok" } else { "FAILED" };
        eprintln!("  {:>6.1}s  {outcome:<6}  {name}", took.as_secs_f64());
    }
}
