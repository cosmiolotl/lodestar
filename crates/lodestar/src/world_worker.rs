//! A single storage thread preserves request order without blocking the tick coordinator.
use std::{
    io,
    sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    },
};

use crate::world_pending::PendingChanges;
use crate::{
    state::{ConnId, Effect},
    world::{Request, WRITE, WorldStore, response},
};
use bytes::Bytes;
use tokio::sync::mpsc;

const MAX_QUEUED_BYTES: usize = 256 * 1024 * 1024;

pub(super) enum Completion {
    Reply(ConnId, Bytes),
    Committed(u64),
}

pub(super) struct WorldWorker {
    sender: mpsc::Sender<(Effect, usize)>,
    queued: Arc<AtomicUsize>,
}

impl WorldWorker {
    pub fn start(
        world: Option<WorldStore>,
    ) -> io::Result<(Self, mpsc::Receiver<io::Result<Completion>>)> {
        let (sender, mut requests) = mpsc::channel::<(Effect, usize)>(4096);
        let (replies, receiver) = mpsc::channel(128);
        let queued = Arc::new(AtomicUsize::new(0));
        let remaining = queued.clone();
        std::thread::Builder::new()
            .name("lodestar-storage".into())
            .spawn(move || {
                let mut storage = Storage {
                    world,
                    frozen: false,
                    pending: PendingChanges::default(),
                };
                while let Some((effect, size)) = requests.blocking_recv() {
                    let result = storage.apply(effect);
                    remaining.fetch_sub(size, Ordering::Relaxed);
                    match result {
                        Ok(Some(reply)) => {
                            if replies.blocking_send(Ok(reply)).is_err() {
                                break;
                            }
                        }
                        Ok(None) => {}
                        Err(error) => {
                            let _ = replies.blocking_send(Err(error));
                            break;
                        }
                    }
                }
            })?;
        Ok((Self { sender, queued }, receiver))
    }

    pub fn submit(&self, effect: Effect) -> io::Result<()> {
        let size = 256
            + match &effect {
                Effect::WorldRequest { request, .. } | Effect::SnapshotRecord { request, .. } => {
                    request.data.len()
                }
                Effect::StorePlayerData { data, .. } => data.len(),
                Effect::StoreGlobal { payload, .. } => payload.len(),
                _ => 0,
            };
        if self.queued.fetch_add(size, Ordering::Relaxed) + size > MAX_QUEUED_BYTES {
            self.queued.fetch_sub(size, Ordering::Relaxed);
            return Err(io::Error::other("world storage queue exceeds 256 MiB"));
        }
        if self.sender.try_send((effect, size)).is_err() {
            self.queued.fetch_sub(size, Ordering::Relaxed);
            return Err(io::Error::other(
                "world storage worker stopped or its queue is full",
            ));
        }
        Ok(())
    }
}

struct Storage {
    world: Option<WorldStore>,
    frozen: bool,
    pending: PendingChanges,
}

impl Storage {
    fn apply(&mut self, effect: Effect) -> io::Result<Option<Completion>> {
        match effect {
            Effect::FreezeWorld => self.frozen = true,
            Effect::CommitWorld { revision } => {
                if let Some(world) = &self.world {
                    world.commit(revision)?;
                    std::mem::take(&mut self.pending).apply(world)?;
                }
                self.frozen = false;
                return Ok(Some(Completion::Committed(revision)));
            }
            Effect::WorldRequest {
                conn,
                request,
                committed,
            } => {
                let payload = self.request(request, committed)?;
                return Ok(Some(Completion::Reply(conn, payload)));
            }
            Effect::SnapshotRecord { conn, request } => {
                let world = self
                    .world
                    .as_ref()
                    .ok_or_else(|| io::Error::other("snapshot without storage"))?;
                if request.dimension == "players" {
                    let uuid = u128::from_str_radix(&request.kind, 16).map_err(io::Error::other)?;
                    world.save_player(uuid, &request.data)?;
                } else {
                    world.write(&request)?;
                }
                return Ok(Some(Completion::Reply(conn, response(request.id, 0, &[]))));
            }
            Effect::StorePlayerData { uuid, data } => {
                if self.frozen {
                    self.pending.player(uuid, data)?;
                } else if let Some(world) = &self.world {
                    world.save_player(uuid, &data)?;
                }
            }
            Effect::StoreGlobal { scope, payload } => {
                if self.frozen {
                    self.pending.append(scope, payload)?;
                } else if let Some(world) = &self.world {
                    world.append(&scope, &payload)?;
                }
            }
            _ => unreachable!("non-storage effect sent to the storage worker"),
        }
        Ok(None)
    }

    fn request(&mut self, request: Request, committed: bool) -> io::Result<Bytes> {
        if self.frozen && self.world.is_some() {
            if request.operation == WRITE {
                let id = request.id;
                self.pending.write(request)?;
                return Ok(response(id, 0, &[]));
            }
            if !committed && request.operation == crate::world::READ {
                if let Some(data) = self.pending.read(&request) {
                    return Ok(response(request.id, 0, &data));
                }
            }
            if !committed && request.operation == crate::world::JOURNAL {
                let offset = ((request.x as u32 as u64) << 32) | request.z as u32 as u64;
                let data = self.pending.journal(
                    self.world.as_ref().unwrap(),
                    &request.dimension,
                    offset,
                )?;
                return Ok(response(request.id, 0, &data));
            }
        }
        crate::world_service::execute(self.world.as_ref(), request, committed)
    }
}
