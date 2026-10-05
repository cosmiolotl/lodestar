//! The proxy's connection to lodestar.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use lode_protocol::{
    Message, PROTOCOL_VERSION, PlacementOutcome, Role, read_message, write_message,
};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, oneshot};
use tracing::{info, warn};

use crate::relay::Control;

const RETRY_DELAY: Duration = Duration::from_secs(1);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(5);
const STATUS_TTL: Duration = Duration::from_secs(1);

/// Players online and total capacity across the cluster.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Status {
    pub online: u32,
    pub capacity: u32,
}

enum Request {
    Place {
        uuid: u128,
        name: String,
        reply: oneshot::Sender<PlacementOutcome>,
    },
    Status {
        reply: oneshot::Sender<Status>,
    },
    /// Something to tell lodestar that needs no answer.
    Notify(Message),
}

enum Pending {
    Place(oneshot::Sender<PlacementOutcome>),
    Status(oneshot::Sender<Status>),
}

/// The relays of the players connected through this proxy, by player.
#[derive(Default)]
struct Relays {
    next_id: u64,
    by_player: HashMap<u128, (u64, mpsc::Sender<Control>)>,
}

pub struct Star {
    requests: mpsc::Sender<Request>,
    /// Server list pings are frequent and unauthenticated; do not let them
    /// turn into load on lodestar.
    status: Mutex<Option<(Instant, Status)>>,
    relays: Arc<Mutex<Relays>>,
}

/// Keeps a relay registered until dropped.
pub struct Registration {
    uuid: u128,
    id: u64,
    relays: Arc<Mutex<Relays>>,
}

impl Drop for Registration {
    fn drop(&mut self) {
        let mut relays = self.relays.lock().unwrap();
        if relays
            .by_player
            .get(&self.uuid)
            .is_some_and(|(id, _)| *id == self.id)
        {
            relays.by_player.remove(&self.uuid);
        }
    }
}

impl Star {
    /// Starts the background connection. Does not wait for it to come up.
    pub fn connect(addr: String, token: String, name: String) -> Star {
        let (requests, queue) = mpsc::channel(1024);
        let relays = Arc::new(Mutex::new(Relays::default()));
        tokio::spawn(run(addr, token, name, queue, relays.clone()));
        Star {
            requests,
            status: Mutex::new(None),
            relays,
        }
    }

    /// Asks lodestar which node a player should join. `None` means lodestar
    /// could not be reached.
    pub async fn place(&self, uuid: u128, name: &str) -> Option<PlacementOutcome> {
        let (reply, answer) = oneshot::channel();
        self.requests
            .send(Request::Place {
                uuid,
                name: name.into(),
                reply,
            })
            .await
            .ok()?;
        tokio::time::timeout(REQUEST_TIMEOUT, answer)
            .await
            .ok()?
            .ok()
    }

    pub async fn status(&self) -> Option<Status> {
        if let Some((at, status)) = *self.status.lock().unwrap()
            && at.elapsed() < STATUS_TTL
        {
            return Some(status);
        }
        let (reply, answer) = oneshot::channel();
        self.requests.send(Request::Status { reply }).await.ok()?;
        let status = tokio::time::timeout(REQUEST_TIMEOUT, answer)
            .await
            .ok()?
            .ok()?;
        *self.status.lock().unwrap() = Some((Instant::now(), status));
        Some(status)
    }

    /// Tells lodestar something. Dropped if lodestar is not there to hear it,
    /// which it then treats as the proxy having failed at whatever it was.
    pub fn notify(&self, msg: Message) {
        let _ = self.requests.try_send(Request::Notify(msg));
    }

    /// Registers a player's relay, to be told what lodestar says about them.
    pub fn register(&self, uuid: u128) -> (Registration, mpsc::Receiver<Control>) {
        let (tx, rx) = mpsc::channel(8);
        let mut relays = self.relays.lock().unwrap();
        relays.next_id += 1;
        let id = relays.next_id;
        relays.by_player.insert(uuid, (id, tx));
        let registration = Registration {
            uuid,
            id,
            relays: self.relays.clone(),
        };
        (registration, rx)
    }
}

async fn run(
    addr: String,
    token: String,
    name: String,
    mut queue: mpsc::Receiver<Request>,
    relays: Arc<Mutex<Relays>>,
) {
    loop {
        match session(&addr, &token, &name, &mut queue, &relays).await {
            Ok(()) => return, // the proxy is shutting down
            Err(e) => warn!("lost lodestar at {addr}: {e:#}"),
        }
        // Fail requests instead of queueing them while lodestar is away.
        let retry = tokio::time::sleep(RETRY_DELAY);
        tokio::pin!(retry);
        loop {
            tokio::select! {
                _ = &mut retry => break,
                request = queue.recv() => if request.is_none() { return },
            }
        }
    }
}

/// Passes what lodestar says about a player on to their relay. Returns what to
/// answer if the player has none here.
fn route(relays: &Mutex<Relays>, msg: Message) -> Option<Message> {
    let (uuid, control) = match msg {
        Message::HandoffDial { uuid, token, addr } => (uuid, Control::Dial { token, addr }),
        Message::HandoffFreeze { uuid } => (uuid, Control::Freeze),
        Message::HandoffSwitch { uuid } => (uuid, Control::Switch),
        Message::HandoffCancel { uuid } => (uuid, Control::Cancel),
        _ => return None,
    };
    let needs_answer = !matches!(control, Control::Cancel);
    let relay = relays
        .lock()
        .unwrap()
        .by_player
        .get(&uuid)
        .map(|(_, tx)| tx.clone());
    match relay {
        Some(tx) if tx.try_send(control).is_ok() => None,
        _ if needs_answer => Some(Message::HandoffFailed { uuid }),
        _ => None,
    }
}

/// Runs one connection. Returns `Ok` only when the request queue is closed.
async fn session(
    addr: &str,
    token: &str,
    name: &str,
    queue: &mut mpsc::Receiver<Request>,
    relays: &Mutex<Relays>,
) -> anyhow::Result<()> {
    let stream = TcpStream::connect(addr).await?;
    stream.set_nodelay(true)?;
    let (mut read, mut write) = stream.into_split();

    let hello = Message::Hello {
        protocol: PROTOCOL_VERSION,
        token: token.into(),
        name: name.into(),
        role: Role::Proxy,
    };
    write_message(&mut write, &hello).await?;
    match read_message(&mut read).await? {
        Some(Message::Welcome { .. }) => info!("connected to lodestar at {addr}"),
        Some(Message::Rejected { reason }) => anyhow::bail!("lodestar rejected us: {reason}"),
        other => anyhow::bail!("unexpected answer to Hello: {other:?}"),
    }

    // Reading a frame is not cancel safe, so it gets a task of its own.
    let (inbound_tx, mut inbound) = mpsc::channel(256);
    let reader = tokio::spawn(async move {
        loop {
            let msg = read_message(&mut read).await;
            let done = !matches!(msg, Ok(Some(_)));
            if inbound_tx.send(msg).await.is_err() || done {
                return;
            }
        }
    });
    let _stop_reader = AbortOnDrop(reader);

    let mut pending: HashMap<u32, Pending> = HashMap::new();
    let mut next_id = 0u32;
    loop {
        tokio::select! {
            request = queue.recv() => {
                let Some(request) = request else { return Ok(()) };
                // Forget requests whose caller has given up.
                pending.retain(|_, p| match p {
                    Pending::Place(reply) => !reply.is_closed(),
                    Pending::Status(reply) => !reply.is_closed(),
                });
                next_id = next_id.wrapping_add(1);
                let msg = match request {
                    Request::Place { uuid, name, reply } => {
                        pending.insert(next_id, Pending::Place(reply));
                        Message::PlaceRequest { request_id: next_id, uuid, name }
                    }
                    Request::Status { reply } => {
                        pending.insert(next_id, Pending::Status(reply));
                        Message::StatusRequest { request_id: next_id }
                    }
                    Request::Notify(msg) => msg,
                };
                write_message(&mut write, &msg).await?;
            }
            msg = inbound.recv() => match msg {
                Some(Ok(Some(Message::Placement { request_id, outcome }))) => {
                    if let Some(Pending::Place(reply)) = pending.remove(&request_id) {
                        let _ = reply.send(outcome);
                    }
                }
                Some(Ok(Some(Message::Status { request_id, online, capacity }))) => {
                    if let Some(Pending::Status(reply)) = pending.remove(&request_id) {
                        let _ = reply.send(Status { online, capacity });
                    }
                }
                Some(Ok(Some(msg))) => {
                    if let Some(answer) = route(relays, msg) {
                        write_message(&mut write, &answer).await?;
                    }
                }
                Some(Ok(None)) | None => anyhow::bail!("connection closed"),
                Some(Err(e)) => return Err(e.into()),
            }
        }
    }
}

struct AbortOnDrop(tokio::task::JoinHandle<()>);

impl Drop for AbortOnDrop {
    fn drop(&mut self) {
        self.0.abort();
    }
}
