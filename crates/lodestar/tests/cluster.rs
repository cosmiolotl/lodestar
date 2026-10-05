//! Drives a real lodestar over loopback sockets.

use std::time::Duration;

use bytes::Bytes;
use lode_protocol::{
    ChunkKey, Demand, Message, NodeId, PROTOCOL_VERSION, PlacementOutcome, Role, read_message,
    write_message,
};
use lodestar::state::Policy;
use tokio::net::{TcpListener, TcpStream};

async fn start() -> std::net::SocketAddr {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let policy = Policy {
        token: "secret".into(),
        heartbeat_timeout: Duration::from_secs(10),
        reservation_ttl: Duration::from_secs(30),
        mspt_limit: Duration::from_millis(45),
        tick_interval: Duration::from_millis(50),
        tick_timeout: Duration::from_secs(600),
        auto_handoff: false,
    };
    tokio::spawn(lodestar::server::serve(listener, policy, None));
    addr
}

struct Client(TcpStream);

impl Client {
    async fn connect(addr: std::net::SocketAddr, token: &str, role: Role) -> Client {
        let mut client = Client(TcpStream::connect(addr).await.unwrap());
        let hello = Message::Hello {
            protocol: PROTOCOL_VERSION,
            token: token.into(),
            name: "test".into(),
            role,
        };
        client.send(hello).await;
        client
    }

    async fn node(addr: std::net::SocketAddr, game_addr: &str) -> (Client, NodeId) {
        let role = Role::Node {
            game_addr: game_addr.into(),
            max_players: 100,
        };
        let mut client = Client::connect(addr, "secret", role).await;
        let Some(Message::Welcome { node_id }) = client.recv().await else {
            panic!("expected Welcome");
        };
        (client, node_id)
    }

    async fn send(&mut self, msg: Message) {
        write_message(&mut self.0, &msg).await.unwrap();
    }

    async fn recv(&mut self) -> Option<Message> {
        tokio::time::timeout(Duration::from_secs(5), read_message(&mut self.0))
            .await
            .expect("timed out waiting for a message")
            .unwrap()
    }

    /// Like `recv_until`, but finishes every tick lodestar starts meanwhile,
    /// as a node does, so that the cluster moves on.
    async fn run_until<T>(&mut self, pick: impl Fn(Message) -> Option<T>) -> T {
        loop {
            match self.recv().await.expect("connection closed") {
                Message::Tick { tick } => self.send(Message::TickDone { tick }).await,
                msg => {
                    if let Some(found) = pick(msg) {
                        return found;
                    }
                }
            }
        }
    }

    /// Skips ahead to the next message `pick` accepts.
    async fn recv_until<T>(&mut self, pick: impl Fn(Message) -> Option<T>) -> T {
        loop {
            let msg = self.recv().await.expect("connection closed");
            if let Some(found) = pick(msg) {
                return found;
            }
        }
    }
}

#[tokio::test]
async fn bad_token_is_turned_away() {
    let addr = start().await;
    let mut client = Client::connect(addr, "wrong", Role::Proxy).await;
    assert!(matches!(
        client.recv().await,
        Some(Message::Rejected { .. })
    ));
    assert_eq!(
        client.recv().await,
        None,
        "lodestar hangs up after rejecting"
    );
}

#[tokio::test]
async fn one_node_simulates_a_region_and_another_takes_over() {
    let addr = start().await;
    let (mut a, a_id) = Client::node(addr, "127.0.0.1:1").await;
    let (mut b, b_id) = Client::node(addr, "127.0.0.1:2").await;
    let owner = |msg| match msg {
        Message::RegionOwner { owner, .. } => Some(owner),
        _ => None,
    };
    for node in [&mut a, &mut b] {
        node.send(Message::ResolveDimension {
            name: "minecraft:overworld".into(),
        })
        .await;
    }

    // A loads a chunk, and is named the owner of its region once a tick is over.
    let chunk = ChunkKey {
        dim: 0,
        x: 3,
        z: -4,
    };
    a.send(Message::Subscribe { chunk }).await;
    let (a_heard, b_heard) = tokio::join!(a.run_until(owner), b.run_until(owner));
    assert_eq!((a_heard, b_heard), (a_id, a_id));

    // B loads it too, so A, the owner, is asked to hold it for B.
    b.send(Message::Subscribe { chunk }).await;
    let demand = a
        .run_until(|msg| match msg {
            Message::ChunkDemand { chunk, demand } => Some((chunk, demand)),
            _ => None,
        })
        .await;
    assert_eq!(demand, (chunk, Demand::Loaded));

    let payload = Bytes::from_static(b"block change");
    a.send(Message::Publish {
        chunk,
        payload: payload.clone(),
    })
    .await;
    let relayed = b
        .run_until(|msg| match msg {
            Message::Relay { from, payload, .. } => Some((from, payload)),
            _ => None,
        })
        .await;
    assert_eq!(relayed, (a_id, payload));

    // Once A is gone, B is named the owner between two of the ticks it runs.
    drop(a);
    assert_eq!(
        b.run_until(owner).await,
        b_id,
        "the replica takes over the region"
    );
}

/// The number of the next tick lodestar starts.
async fn next_tick(node: &mut Client) -> u64 {
    node.recv_until(|msg| match msg {
        Message::Tick { tick } => Some(tick),
        _ => None,
    })
    .await
}

#[tokio::test]
async fn ticks_run_in_lockstep() {
    let addr = start().await;
    let (mut a, _) = Client::node(addr, "127.0.0.1:1").await;
    let alone = next_tick(&mut a).await;
    // B arrives in the middle of a tick, and is not waited for until the next.
    let (mut b, _) = Client::node(addr, "127.0.0.1:2").await;
    a.send(Message::TickDone { tick: alone }).await;
    let first = next_tick(&mut b).await;
    assert_eq!(first, alone + 1);
    assert_eq!(next_tick(&mut a).await, first);

    // A is done, B is not: the cluster waits.
    a.send(Message::TickDone { tick: first }).await;
    let early = tokio::time::timeout(Duration::from_millis(300), next_tick(&mut a)).await;
    assert!(
        early.is_err(),
        "tick {} started before B finished",
        first + 1
    );

    b.send(Message::TickDone { tick: first }).await;
    assert_eq!(next_tick(&mut a).await, first + 1);
    assert_eq!(next_tick(&mut b).await, first + 1);
}

#[tokio::test]
async fn proxy_places_players_across_nodes() {
    let addr = start().await;
    let (_a, a_id) = Client::node(addr, "127.0.0.1:1").await;
    let (_b, b_id) = Client::node(addr, "127.0.0.1:2").await;
    let mut proxy = Client::connect(addr, "secret", Role::Proxy).await;
    assert_eq!(proxy.recv().await, Some(Message::Welcome { node_id: 0 }));

    let mut placed = Vec::new();
    for uuid in 0..4u128 {
        proxy
            .send(Message::PlaceRequest {
                request_id: uuid as u32,
                uuid,
                name: "p".into(),
            })
            .await;
        match proxy.recv().await {
            Some(Message::Placement {
                outcome: PlacementOutcome::Node { node, .. },
                ..
            }) => placed.push(node),
            other => panic!("expected a placement, got {other:?}"),
        }
    }
    assert_eq!(placed, [a_id, b_id, a_id, b_id]);

    proxy.send(Message::StatusRequest { request_id: 9 }).await;
    assert_eq!(
        proxy.recv().await,
        Some(Message::Status {
            request_id: 9,
            online: 0,
            capacity: 200
        })
    );
}
