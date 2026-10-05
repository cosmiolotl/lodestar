use std::path::Path;

use anyhow::{Context, bail};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Config {
    /// Address players connect to.
    pub bind: String,
    /// Address of lodestar.
    pub lodestar: String,
    /// lodestar's `token`.
    pub token: String,
    /// Secret shared with every node, used to sign forwarded player identities.
    pub forwarding_secret: String,
    /// Authenticate players with Mojang. Turn off only for testing.
    pub online_mode: bool,
    /// Shown in the server list.
    pub motd: String,
    /// Name this proxy reports to lodestar.
    pub name: String,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            bind: "0.0.0.0:25565".into(),
            lodestar: "127.0.0.1:25580".into(),
            token: String::new(),
            forwarding_secret: String::new(),
            online_mode: true,
            motd: "A Lodestar cluster".into(),
            name: "proxy".into(),
        }
    }
}

impl Config {
    /// Loads the config. If there is none, writes a template and asks for it
    /// to be filled in: the secrets have to come from the rest of the cluster.
    pub fn load(path: &Path) -> anyhow::Result<Config> {
        if !path.exists() {
            std::fs::write(path, toml::to_string_pretty(&Config::default())?)
                .with_context(|| format!("writing {}", path.display()))?;
            bail!(
                "wrote a template config to {}; set `token` and `forwarding_secret`",
                path.display()
            );
        }
        let text =
            std::fs::read_to_string(path).with_context(|| format!("reading {}", path.display()))?;
        let config: Config =
            toml::from_str(&text).with_context(|| format!("parsing {}", path.display()))?;
        if config.token.is_empty() || config.forwarding_secret.is_empty() {
            bail!(
                "`token` and `forwarding_secret` must be set in {}",
                path.display()
            );
        }
        Ok(config)
    }
}
