use std::path::PathBuf;

use anyhow::Context;
use lodestar::config::Config;
use lodestar::storage::PlayerStore;
use tokio::net::TcpListener;
use tracing::info;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt::init();

    let path = std::env::args_os()
        .nth(1)
        .map_or_else(|| PathBuf::from("lodestar.toml"), PathBuf::from);
    let config = Config::load_or_create(&path)?;

    let listener = TcpListener::bind(&config.bind)
        .await
        .with_context(|| format!("binding {}", config.bind))?;
    let store = PlayerStore::open(&config.data_dir)
        .with_context(|| format!("opening {}", config.data_dir))?;
    info!("lodestar listening on {}", listener.local_addr()?);

    tokio::select! {
        result = lodestar::server::serve(listener, config.policy(), Some(store)) => result?,
        _ = tokio::signal::ctrl_c() => info!("shutting down"),
    }
    Ok(())
}
