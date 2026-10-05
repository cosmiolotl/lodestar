//! Where lodestar keeps what must outlive it: each player's saved data, one
//! file per player, and the next block of entity ids, map ids and raid ids to
//! hand out.
//!
//! Writes go through one task in the order they were made, each to a
//! temporary file that then replaces the old one, so a crash leaves either the
//! old data or the new, never half of either.

use std::path::{Path, PathBuf};

use bytes::Bytes;
use tokio::sync::mpsc;
use tracing::warn;

pub struct PlayerStore {
    dir: PathBuf,
    id_blocks: PathBuf,
    counter_blocks: PathBuf,
}

/// The next entity id block to hand out. Nodes keep running while lodestar
/// restarts, and their entities keep their ids, so a restarted lodestar must
/// not hand those ids out again.
pub struct IdBlockStore {
    path: PathBuf,
}

impl IdBlockStore {
    pub fn load(&self) -> std::io::Result<Option<u32>> {
        match std::fs::read_to_string(&self.path) {
            Ok(text) => text
                .trim()
                .parse()
                .map(Some)
                .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e)),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(e),
        }
    }

    /// Blocks are asked for rarely, each one being thousands of entities, so
    /// this writes right away.
    pub fn store(&self, next: u32) -> std::io::Result<()> {
        crate::world::atomic_write(&self.path, next.to_string().as_bytes())
    }
}

/// The next block of each counter to hand out, one line per counter: its
/// number and the block. Like entity ids, map ids and raid ids live on in the
/// nodes' saves while lodestar restarts.
pub struct CounterBlockStore {
    path: PathBuf,
}

impl CounterBlockStore {
    /// The next block of each counter, by counter; counters that were never
    /// written down are left out.
    pub fn load(&self) -> std::io::Result<Vec<(u8, u32)>> {
        let text = match std::fs::read_to_string(&self.path) {
            Ok(text) => text,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(e) => return Err(e),
        };
        let invalid = |line: &str| {
            std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                format!("not a counter and a block: {line:?}"),
            )
        };
        text.lines()
            .filter(|line| !line.trim().is_empty())
            .map(|line| {
                let mut words = line.split_whitespace();
                let counter = words.next().and_then(|w| w.parse().ok());
                let block = words.next().and_then(|w| w.parse().ok());
                counter.zip(block).ok_or_else(|| invalid(line))
            })
            .collect()
    }

    pub fn store(&self, next: &[u32]) -> std::io::Result<()> {
        let text: String = next
            .iter()
            .enumerate()
            .map(|(counter, block)| format!("{counter} {block}\n"))
            .collect();
        crate::world::atomic_write(&self.path, text.as_bytes())
    }
}

fn file_name(uuid: u128) -> String {
    let hex = format!("{uuid:032x}");
    format!(
        "{}-{}-{}-{}-{}.dat",
        &hex[..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..]
    )
}

fn parse_name(name: &str) -> Option<u128> {
    let stem = name.strip_suffix(".dat")?;
    let hex: String = stem.chars().filter(|c| *c != '-').collect();
    if hex.len() != 32 {
        return None;
    }
    u128::from_str_radix(&hex, 16).ok()
}

impl PlayerStore {
    pub fn open(dir: impl AsRef<Path>) -> std::io::Result<PlayerStore> {
        let id_blocks = dir.as_ref().join("entity-id-blocks");
        let counter_blocks = dir.as_ref().join("counter-blocks");
        let dir = dir.as_ref().join("players");
        std::fs::create_dir_all(&dir)?;
        Ok(PlayerStore {
            dir,
            id_blocks,
            counter_blocks,
        })
    }

    pub fn world(&self) -> std::io::Result<crate::world::WorldStore> {
        crate::world::WorldStore::open(self.dir.parent().unwrap())
    }

    pub fn id_blocks(&self) -> IdBlockStore {
        IdBlockStore {
            path: self.id_blocks.clone(),
        }
    }

    pub fn counter_blocks(&self) -> CounterBlockStore {
        CounterBlockStore {
            path: self.counter_blocks.clone(),
        }
    }

    /// Everything stored, for the state to start from.
    pub fn load_all(&self) -> std::io::Result<Vec<(u128, Bytes)>> {
        let mut players = Vec::new();
        for entry in std::fs::read_dir(&self.dir)? {
            let entry = entry?;
            let name = entry.file_name();
            if let Some(uuid) = name.to_str().and_then(parse_name) {
                players.push((uuid, Bytes::from(std::fs::read(entry.path())?)));
            }
        }
        Ok(players)
    }

    pub fn save(&self, uuid: u128, data: &[u8]) -> std::io::Result<()> {
        crate::world::atomic_write(&self.dir.join(file_name(uuid)), data)
    }

    /// Starts the task that writes saves, and returns where to send them.
    pub fn spawn_writer(self) -> mpsc::UnboundedSender<(u128, Bytes)> {
        let (tx, mut rx) = mpsc::unbounded_channel::<(u128, Bytes)>();
        tokio::spawn(async move {
            while let Some((uuid, data)) = rx.recv().await {
                let dir = self.dir.clone();
                let written = tokio::task::spawn_blocking(move || write(&dir, uuid, &data)).await;
                if let Ok(Err(e)) | Err(e) = written.map_err(std::io::Error::other) {
                    warn!("could not store the data of player {uuid:032x}: {e}");
                }
            }
        });
        tx
    }
}

fn write(dir: &Path, uuid: u128, data: &[u8]) -> std::io::Result<()> {
    let path = dir.join(file_name(uuid));
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, data)?;
    std::fs::rename(&tmp, &path)
}
