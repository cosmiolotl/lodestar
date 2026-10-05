use std::path::Path;
use std::time::Duration;

use anyhow::{Context, bail};
use rand::RngCore;
use serde::{Deserialize, Serialize};

use crate::state::Policy;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Config {
    /// Address nodes and proxies connect to. Keep this on a private network.
    pub bind: String,
    /// Shared secret every node and proxy must present.
    pub token: String,
    /// A node that has been silent for this long stops receiving new players.
    pub heartbeat_timeout_secs: u64,
    /// How long a placement holds a slot while the player is still logging in.
    pub reservation_secs: u64,
    /// Nodes ticking slower than this are avoided when placing players.
    pub mspt_limit_ms: u64,
    /// The shortest time between the starts of two cluster ticks. 50 ms is
    /// Minecraft's usual 20 ticks per second.
    pub tick_interval_ms: u64,
    /// A node that takes longer than this to finish a tick is dropped from the
    /// cluster rather than being allowed to stall it.
    pub tick_timeout_secs: u64,
    /// Where lodestar keeps the authoritative world, player data and ID allocations.
    pub data_dir: String,
    /// Move connected players between nodes without a reconnect: to home
    /// them on the node that simulates where they are, and to even out how
    /// many players each node has.
    pub auto_handoff: bool,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            bind: "0.0.0.0:25580".into(),
            token: String::new(),
            heartbeat_timeout_secs: 10,
            reservation_secs: 30,
            mspt_limit_ms: 45,
            tick_interval_ms: 50,
            tick_timeout_secs: 10,
            data_dir: "lodestar-data".into(),
            auto_handoff: true,
        }
    }
}

impl Config {
    /// Loads the config, writing a fresh one with a random token if none exists.
    pub fn load_or_create(path: &Path) -> anyhow::Result<Config> {
        if !path.exists() {
            let mut token = [0u8; 24];
            rand::thread_rng().fill_bytes(&mut token);
            let config = Config {
                token: token.iter().map(|b| format!("{b:02x}")).collect(),
                ..Config::default()
            };
            std::fs::write(path, toml::to_string_pretty(&config)?)
                .with_context(|| format!("writing {}", path.display()))?;
            tracing::info!(
                "wrote a new config with a generated token to {}",
                path.display()
            );
            return Ok(config);
        }
        let text =
            std::fs::read_to_string(path).with_context(|| format!("reading {}", path.display()))?;
        let config: Config =
            toml::from_str(&text).with_context(|| format!("parsing {}", path.display()))?;
        if config.token.is_empty() {
            bail!("`token` must be set in {}", path.display());
        }
        Ok(config)
    }

    pub fn policy(&self) -> Policy {
        Policy {
            token: self.token.clone(),
            heartbeat_timeout: Duration::from_secs(self.heartbeat_timeout_secs),
            reservation_ttl: Duration::from_secs(self.reservation_secs),
            mspt_limit: Duration::from_millis(self.mspt_limit_ms),
            tick_interval: Duration::from_millis(self.tick_interval_ms),
            tick_timeout: Duration::from_secs(self.tick_timeout_secs),
            auto_handoff: self.auto_handoff,
        }
    }
}
