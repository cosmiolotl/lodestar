//! Harness for Lodestar's end-to-end tests.
//!
//! A test starts a real cluster: the `lodestar` and `lodeproxy` programs, and
//! Fabric servers running the Lodecore jar, each in a directory of its own
//! under `target/e2e/runs/`. It drives the nodes with RCON, the way an operator
//! would, and logs in scripted players through the proxy, the way a game
//! client would. Nothing is faked or called in process.
//!
//! [`setup`] builds or finds what that takes, once per run.

pub mod client;
pub mod cluster;
mod process;
pub mod rcon;
pub mod setup;
mod wire;

use std::time::{Duration, Instant};

pub use client::Client;
pub use cluster::{Cluster, Node, Options};

/// Polls `check` until it returns `Ok`, and panics with its last `Err` if
/// that takes longer than `timeout`. For what a cluster does a few ticks or a
/// network hop later, which a test cannot wait for any other way.
pub fn wait_for<T>(what: &str, timeout: Duration, mut check: impl FnMut() -> Result<T, String>) -> T {
    let deadline = Instant::now() + timeout;
    loop {
        match check() {
            Ok(value) => return value,
            Err(last) if Instant::now() >= deadline => {
                panic!("timed out after {timeout:?} waiting for {what}; last: {last}")
            }
            Err(_) => std::thread::sleep(Duration::from_millis(200)),
        }
    }
}

/// [`wait_for`], for a check that has nothing to report but yes or no.
pub fn wait_until(what: &str, timeout: Duration, mut check: impl FnMut() -> bool) {
    wait_for(what, timeout, || if check() { Ok(()) } else { Err("not yet".into()) });
}

/// Checks that `check` holds the whole time, for something that must not
/// happen, and panics as soon as it does not.
pub fn stays(what: &str, period: Duration, mut check: impl FnMut() -> Result<(), String>) {
    let deadline = Instant::now() + period;
    while Instant::now() < deadline {
        if let Err(detail) = check() {
            panic!("{what} stopped holding: {detail}");
        }
        std::thread::sleep(Duration::from_millis(200));
    }
}

/// Runs a closure when dropped, to put back what a test changed for the
/// whole cluster even if the test fails.
pub struct Defer<F: FnMut()>(pub F);

impl<F: FnMut()> Drop for Defer<F> {
    fn drop(&mut self) {
        (self.0)();
    }
}

pub fn secs(n: u64) -> Duration {
    Duration::from_secs(n)
}

/// The UUID a server in offline mode gives a player of this name, as
/// commands take it.
pub fn offline_uuid(name: &str) -> String {
    let id = lodeproxy::auth::offline_uuid(name);
    let hex = format!("{id:032x}");
    format!("{}-{}-{}-{}-{}", &hex[..8], &hex[8..12], &hex[12..16], &hex[16..20], &hex[20..])
}
