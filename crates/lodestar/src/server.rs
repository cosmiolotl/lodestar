//! Socket plumbing around [`State`].
//!
//! One task owns the state and processes events in order, and runs the tick
//! clock between them. Each connection has a reader task that turns frames
//! into events and a writer task that drains a bounded queue of encoded frames.

use std::collections::HashMap;
use std::time::{Duration, Instant};

use bytes::Bytes;
use lode_protocol::{Message, read_message};
use tokio::io::{AsyncWriteExt, BufReader, BufWriter};
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;
use tokio::task::AbortHandle;
use tracing::{debug, warn};

use crate::state::{ConnId, Effect, Policy, State};
use crate::storage::{CounterBlockStore, IdBlockStore, PlayerStore};

/// Frames a connection may have queued before it is considered stuck and dropped.
/// Queue exhaustion fences a durable cluster rather than silently losing replication.
const OUTBOUND_QUEUE: usize = 16 * 1024;
const EVENT_QUEUE: usize = 64 * 1024;
const HELLO_TIMEOUT: Duration = Duration::from_secs(5);

enum Event {
    Message(ConnId, Message),
    Closed(ConnId),
}

struct Conn {
    outbound: mpsc::Sender<Bytes>,
    reader: AbortHandle,
}

/// Accepts connections on `listener` until the task is cancelled. Players'
/// data is kept in `store`, or only in memory if there is none.
pub async fn serve(
    listener: TcpListener,
    policy: Policy,
    store: Option<PlayerStore>,
) -> std::io::Result<()> {
    let (events_tx, mut events) = mpsc::channel(EVENT_QUEUE);
    let mut state = State::new(policy);
    let world = store.as_ref().map(PlayerStore::world).transpose()?;
    if let Some(world) = &world {
        state.enable_checkpoints(world.revision()?, world.epoch()?);
        for (uuid, data) in world.players()? {
            state.load_player_data(uuid, data);
        }
    }
    let (id_blocks, counter_blocks) = match store {
        Some(store) => {
            let id_blocks = store.id_blocks();
            if let Some(next) = id_blocks.load()? {
                state.load_id_blocks(next);
            }
            let counter_blocks = store.counter_blocks();
            let mut next = Vec::new();
            for (counter, block) in counter_blocks.load()? {
                let counter = counter as usize;
                if next.len() <= counter {
                    next.resize(counter + 1, 0);
                }
                next[counter] = block;
            }
            state.load_counter_blocks(&next);
            (Some(id_blocks), Some(counter_blocks))
        }
        None => (None, None),
    };
    let (world, mut storage_events) = crate::world_worker::WorldWorker::start(world)?;
    let stores = Stores {
        world,
        id_blocks,
        counter_blocks,
    };
    let mut conns: HashMap<ConnId, Conn> = HashMap::new();
    let mut next_conn: ConnId = 1;
    // When the clock next needs a look, if ever.
    let mut wake: Option<Instant> = None;

    loop {
        let mut effects = Vec::new();
        let alarm = async {
            match wake {
                Some(at) => tokio::time::sleep_until(at.into()).await,
                None => std::future::pending().await,
            }
        };
        tokio::select! {
            () = alarm => {}
            accepted = listener.accept() => {
                let (stream, addr) = match accepted {
                    Ok(accepted) => accepted,
                    Err(e) => {
                        warn!("accept failed: {e}");
                        continue;
                    }
                };
                let conn = next_conn;
                next_conn += 1;
                debug!("connection {conn} from {addr}");
                conns.insert(conn, spawn_conn(conn, stream, events_tx.clone()));
            }
            completion = storage_events.recv() => {
                let completion = completion.ok_or_else(|| std::io::Error::other("world storage thread stopped"))??;
                match completion {
                    crate::world_worker::Completion::Committed(revision) => state.world_committed(revision, &mut effects),
                    crate::world_worker::Completion::Reply(conn, payload) => effects.push(Effect::Send(
                        conn, Message::DirectRelay { from: 0, payload }.to_frame(),
                    )),
                }
            }
            Some(event) = events.recv() => match event {
                // A reader can still deliver a message after its connection was dropped.
                Event::Message(conn, msg) => {
                    if conns.contains_key(&conn) {
                        state.handle(conn, msg, Instant::now(), &mut effects);
                    }
                }
                Event::Closed(conn) => effects.push(Effect::Close(conn)),
            },
        }

        loop {
            apply(&mut state, &mut conns, &stores, &mut effects)?;
            wake = state.poll_clock(Instant::now(), &mut effects);
            if effects.is_empty() {
                break;
            }
        }
    }
}

/// Where effects that outlive lodestar are written, if anywhere.
struct Stores {
    world: crate::world_worker::WorldWorker,
    id_blocks: Option<IdBlockStore>,
    counter_blocks: Option<CounterBlockStore>,
}

/// Carries out effects, and whatever further effects that leads to.
fn apply(
    state: &mut State,
    conns: &mut HashMap<ConnId, Conn>,
    stores: &Stores,
    effects: &mut Vec<Effect>,
) -> std::io::Result<()> {
    while !effects.is_empty() {
        for effect in std::mem::take(effects) {
            match effect {
                effect @ (Effect::CommitWorld { .. }
                | Effect::FreezeWorld
                | Effect::WorldRequest { .. }
                | Effect::SnapshotRecord { .. }
                | Effect::StoreGlobal { .. }
                | Effect::StorePlayerData { .. }) => {
                    stores.world.submit(effect)?;
                }
                Effect::Send(conn, frame) => {
                    let Some(target) = conns.get(&conn) else {
                        continue;
                    };
                    if target.outbound.try_send(frame).is_err() {
                        warn!("connection {conn} is not keeping up, dropping it");
                        effects.push(Effect::Close(conn));
                    }
                }
                Effect::StoreIdBlocks { next } => {
                    if let Some(id_blocks) = &stores.id_blocks {
                        id_blocks.store(next)?;
                    }
                }
                Effect::StoreCounterBlocks { next } => {
                    if let Some(counter_blocks) = &stores.counter_blocks {
                        counter_blocks.store(&next)?;
                    }
                }
                Effect::Close(conn) => {
                    if state.unexpected_worker_loss(conn) {
                        return Err(std::io::Error::other(
                            "worker lost before a clean departure; restart the cluster from the last committed world revision",
                        ));
                    }
                    // Dropping the queue lets the writer flush what is left and hang up.
                    if let Some(closed) = conns.remove(&conn) {
                        closed.reader.abort();
                        state.disconnect(conn, effects);
                    }
                }
            }
        }
    }
    Ok(())
}

fn spawn_conn(conn: ConnId, stream: TcpStream, events: mpsc::Sender<Event>) -> Conn {
    let _ = stream.set_nodelay(true);
    let (read, write) = stream.into_split();
    let (outbound, queue) = mpsc::channel(OUTBOUND_QUEUE);
    tokio::spawn(write_loop(write, queue));
    let reader = tokio::spawn(async move {
        if let Err(e) = read_loop(conn, read, &events).await {
            debug!("connection {conn} failed: {e}");
        }
        let _ = events.send(Event::Closed(conn)).await;
    });
    Conn {
        outbound,
        reader: reader.abort_handle(),
    }
}

async fn read_loop(
    conn: ConnId,
    read: OwnedReadHalf,
    events: &mpsc::Sender<Event>,
) -> std::io::Result<()> {
    // Workers pipeline frames. Read them in blocks instead of a socket read for each header/body.
    let mut read = BufReader::with_capacity(64 * 1024, read);
    let hello = tokio::time::timeout(HELLO_TIMEOUT, read_message(&mut read))
        .await
        .map_err(|_| std::io::Error::new(std::io::ErrorKind::TimedOut, "no Hello received"))??;
    let mut next = hello;
    while let Some(msg) = next {
        if events.send(Event::Message(conn, msg)).await.is_err() {
            break;
        }
        next = read_message(&mut read).await?;
    }
    Ok(())
}

async fn write_loop(write: OwnedWriteHalf, mut queue: mpsc::Receiver<Bytes>) {
    let mut write = BufWriter::new(write);
    while let Some(frame) = queue.recv().await {
        if write.write_all(&frame).await.is_err() {
            return;
        }
        // Coalesce whatever is already waiting into one flush.
        while let Ok(frame) = queue.try_recv() {
            if write.write_all(&frame).await.is_err() {
                return;
            }
        }
        if write.flush().await.is_err() {
            return;
        }
    }
    let _ = write.shutdown().await;
}
