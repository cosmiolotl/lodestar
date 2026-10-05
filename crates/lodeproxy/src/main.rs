use std::path::PathBuf;
use std::sync::Arc;

use anyhow::Context;
use lodeproxy::config::Config;
use lodeproxy::session::{self, Proxy};
use tokio::net::TcpListener;
use tracing::{info, warn};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt::init();

    let path = std::env::args_os()
        .nth(1)
        .map_or_else(|| PathBuf::from("lodeproxy.toml"), PathBuf::from);
    let config = Config::load(&path)?;
    if !config.online_mode {
        warn!("online_mode is off: players are NOT authenticated and can join under any name");
    }

    let listener = TcpListener::bind(&config.bind)
        .await
        .with_context(|| format!("binding {}", config.bind))?;
    info!("lodeproxy listening on {}", listener.local_addr()?);

    let proxy = Arc::new(Proxy::new(config)?);
    tokio::select! {
        () = session::serve(listener, proxy) => {}
        _ = tokio::signal::ctrl_c() => info!("shutting down"),
    }
    Ok(())
}
