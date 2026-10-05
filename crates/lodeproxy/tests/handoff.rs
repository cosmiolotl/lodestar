//! Moves a player across three scripted nodes through a real lodestar and a
//! real proxy, and checks that the client neither notices nor loses a packet.

use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use bytes::Bytes;
use lode_protocol::{
    ChunkKey, Message, NodeId, PROTOCOL_VERSION, Role, read_message, write_message,
};
use lodeproxy::auth::offline_uuid;
use lodeproxy::config::Config;
use lodeproxy::mc::{self, Conn, get_string, get_u128, get_varint, put_string, put_varint};
use lodeproxy::session::{self, Proxy};
use lodestar::state::Policy;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

const TOKEN: &str = "cluster-token";
const SECRET: &str = "forwarding-secret";
const PROTOCOL: i32 = 775;
const THRESHOLD: i32 = 64;
/// What a node a player moves to sends first, plus its number.
const WELCOME: i32 = 0x40;
/// What a node a player moves from sends last, plus its number.
const GOODBYE: i32 = 0x70;

async fn cluster() -> (SocketAddr, SocketAddr) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let star = listener.local_addr().unwrap();
    let policy = Policy {
        token: TOKEN.into(),
        heartbeat_timeout: Duration::from_secs(10),
        reservation_ttl: Duration::from_secs(30),
        mspt_limit: Duration::from_millis(45),
        tick_interval: Duration::from_millis(50),
        tick_timeout: Duration::from_secs(600),
        auto_handoff: false,
    };
    tokio::spawn(lodestar::server::serve(listener, policy, None));

    let config = Config {
        lodestar: star.to_string(),
        token: TOKEN.into(),
        forwarding_secret: SECRET.into(),
        online_mode: false,
        ..Config::default()
    };
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let proxy = listener.local_addr().unwrap();
    let handle = Arc::new(Proxy::new(config).unwrap());
    tokio::spawn(session::serve(listener, handle.clone()));
    while handle.star.status().await.is_none() {
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    (star, proxy)
}

/// A scripted node: its connection to lodestar, as a worker of dimension 0,
/// and its game port.
struct FakeNode {
    id: NodeId,
    star: TcpStream,
    game: TcpListener,
}

async fn fake_node(star: SocketAddr, name: &str) -> FakeNode {
    let game = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let mut stream = TcpStream::connect(star).await.unwrap();
    let hello = Message::Hello {
        protocol: PROTOCOL_VERSION,
        token: TOKEN.into(),
        name: name.into(),
        role: Role::Node {
            game_addr: game.local_addr().unwrap().to_string(),
            max_players: 50,
        },
    };
    write_message(&mut stream, &hello).await.unwrap();
    let id = match read_message(&mut stream).await {
        Ok(Some(Message::Welcome { node_id })) => node_id,
        other => panic!("expected a welcome, got {other:?}"),
    };
    let resolve = Message::ResolveDimension {
        name: "overworld".into(),
    };
    write_message(&mut stream, &resolve).await.unwrap();
    FakeNode {
        id,
        star: stream,
        game,
    }
}

/// Reads lodestar's messages until one matches.
async fn expect<T>(stream: &mut TcpStream, mut want: impl FnMut(Message) -> Option<T>) -> T {
    loop {
        let msg = tokio::time::timeout(Duration::from_secs(5), read_message(stream))
            .await
            .expect("timed out waiting for lodestar")
            .unwrap()
            .expect("lodestar hung up");
        if let Some(found) = want(msg) {
            return found;
        }
    }
}

async fn tell(stream: &mut TcpStream, msg: Message) {
    write_message(stream, &msg).await.unwrap();
}

/// Answers the proxy's login as the Lodecore mod does. Returns the player's
/// uuid, the token the proxy presented if any, and how many packets the
/// proxy has written so far.
async fn accept_login(conn: &mut Conn) -> (u128, Option<u64>, u64) {
    let (id, mut handshake) = conn.read_packet().await.unwrap();
    assert_eq!(id, 0);
    assert_eq!(get_varint(&mut handshake).unwrap(), PROTOCOL);
    let (_, mut start) = conn.read_packet().await.unwrap();
    let name = get_string(&mut start, 16).unwrap();
    let uuid = get_u128(&mut start).unwrap();

    let mut token = None;
    for (message_id, channel) in [(1, "velocity:player_info"), (2, "lodecore:handoff")] {
        let mut out = Vec::new();
        put_varint(&mut out, message_id);
        put_string(&mut out, channel);
        conn.write_packet(0x04, &out).await.unwrap();
        let (id, mut answer) = conn.read_packet().await.unwrap();
        assert_eq!(id, 0x02);
        assert_eq!(get_varint(&mut answer).unwrap(), message_id);
        let understood = answer.split_to(1)[0] == 1;
        if channel == "lodecore:handoff" && understood {
            token = Some(u64::from_be_bytes(answer[..8].try_into().unwrap()));
        }
    }

    let mut out = Vec::new();
    put_varint(&mut out, THRESHOLD);
    conn.write_packet(0x03, &out).await.unwrap();
    conn.set_compression(THRESHOLD);
    let mut out = uuid.to_be_bytes().to_vec();
    put_string(&mut out, &name);
    put_varint(&mut out, 0);
    conn.write_packet(0x02, &out).await.unwrap();
    let mut frames = 4; // handshake, login start and two plugin answers
    if token.is_some() {
        assert_eq!(
            read(conn).await.0,
            0x03,
            "the proxy ends the login, as the client would"
        );
        frames += 1;
    }
    (uuid, token, frames)
}

async fn read(conn: &mut Conn) -> (i32, Bytes) {
    tokio::time::timeout(Duration::from_secs(5), conn.read_packet())
        .await
        .expect("timed out waiting for a packet")
        .unwrap()
}

/// A node serving the player: it greets them if they arrived from another
/// node, echoes every packet with its id plus `number`, and counts them. Told
/// to let go of the player once it has read a number of packets, it waits for
/// exactly that many, says goodbye and hangs up.
struct Serving {
    read: Arc<AtomicU64>,
    cut: mpsc::Sender<u64>,
    gone: mpsc::Receiver<()>,
    task: JoinHandle<()>,
}

fn serve_player(mut conn: Conn, number: i32, logged_in: u64, greet: bool) -> Serving {
    let read = Arc::new(AtomicU64::new(logged_in));
    let (cut, mut cut_rx) = mpsc::channel::<u64>(1);
    let (gone_tx, gone) = mpsc::channel::<()>(1);
    let counted = read.clone();
    let task = tokio::spawn(async move {
        if greet {
            conn.write_packet(WELCOME + number, b"welcome")
                .await
                .unwrap();
        }
        let mut cut_at = None;
        loop {
            if let Some(frames) = cut_at
                && counted.load(Ordering::SeqCst) == frames
            {
                conn.write_packet(GOODBYE + number, &[]).await.unwrap();
                drop(conn);
                let _ = gone_tx.send(()).await;
                return;
            }
            tokio::select! {
                frames = cut_rx.recv(), if cut_at.is_none() => cut_at = frames,
                packet = conn.read_packet() => {
                    let Ok((id, payload)) = packet else { return };
                    counted.fetch_add(1, Ordering::SeqCst);
                    conn.write_packet(id + number, &payload).await.unwrap();
                }
            }
        }
    });
    Serving {
        read,
        cut,
        gone,
        task,
    }
}

/// Moves the player from one node to another, the way the Lodecore mod and
/// lodestar do it, while the client sends `sent` packets. Returns the new
/// node's service.
async fn move_player(
    uuid: u128,
    client: &mut Conn,
    from: &mut FakeNode,
    serving: &mut Serving,
    to: &mut FakeNode,
    number: i32,
    sent: &mut u8,
) -> Serving {
    tell(&mut from.star, Message::HandoffRequest { uuid, to: to.id }).await;
    let token = expect(&mut to.star, |m| match m {
        Message::HandoffPrepare { token, .. } => Some(token),
        _ => None,
    })
    .await;
    let (stream, _) = to.game.accept().await.unwrap();
    let mut conn = Conn::new(stream, mc::MAX_FRAME);
    let (_, presented, logged_in) = accept_login(&mut conn).await;
    assert_eq!(
        presented,
        Some(token),
        "the proxy presents lodestar's token"
    );

    // What the client sends meanwhile reaches one node or the other, once.
    for _ in 0..5 {
        client.write_packet(0x20, &[*sent]).await.unwrap();
        *sent += 1;
    }
    tell(&mut to.star, Message::HandoffReady { uuid }).await;
    let frames = expect(&mut from.star, |m| match m {
        Message::HandoffCut { frames, .. } => Some(frames),
        _ => None,
    })
    .await;
    serving.cut.send(frames).await.unwrap();
    tokio::time::timeout(Duration::from_secs(5), serving.gone.recv())
        .await
        .expect("the node read exactly the packets the proxy said it had sent")
        .unwrap();
    assert_eq!(serving.read.load(Ordering::SeqCst), frames);
    tell(
        &mut from.star,
        Message::HandoffState {
            uuid,
            data: Bytes::from_static(b"data"),
            state: Bytes::from_static(b"state"),
        },
    )
    .await;
    let arrived = expect(&mut to.star, |m| match m {
        Message::HandoffArrive { state, .. } => Some(state),
        _ => None,
    })
    .await;
    assert_eq!(&arrived[..], b"state");
    let next = serve_player(conn, number, logged_in, true);
    tell(&mut to.star, Message::HandoffDone { uuid }).await;
    next
}

/// Reads what the client is sent until the new node has answered every
/// packet the client sent during the move, and checks the order: the old
/// node is heard out before the new one is heard at all.
async fn hear_move(client: &mut Conn, old: i32, new: i32, from: u8, to: u8) {
    let mut answered = Vec::new();
    let (mut said_goodbye, mut welcomed) = (false, false);
    while answered.len() < usize::from(to - from) || !welcomed {
        let (id, payload) = read(client).await;
        match id - 0x20 {
            _ if id == GOODBYE + old => said_goodbye = true,
            _ if id == WELCOME + new => {
                assert!(
                    said_goodbye,
                    "the new node is heard only after the old one has finished"
                );
                welcomed = true;
            }
            bump if bump == old => {
                assert!(!said_goodbye);
                answered.push(payload[0]);
            }
            bump if bump == new => {
                assert!(welcomed, "the new node answers after its own first packet");
                answered.push(payload[0]);
            }
            _ => panic!("unexpected packet {id:#x}"),
        }
    }
    assert_eq!(answered, (from..to).collect::<Vec<u8>>());
}

#[tokio::test]
async fn a_player_moves_between_nodes_without_their_client_noticing() {
    let (star, proxy) = cluster().await;
    let mut a = fake_node(star, "a").await;
    let mut b = fake_node(star, "b").await;
    let mut c = fake_node(star, "c").await;

    // The client logs in, and is placed on node a, the first to join.
    let mut client = Conn::new(TcpStream::connect(proxy).await.unwrap(), mc::MAX_FRAME);
    let mut out = Vec::new();
    put_varint(&mut out, PROTOCOL);
    put_string(&mut out, "play.example.com");
    out.extend_from_slice(&25565u16.to_be_bytes());
    put_varint(&mut out, 2);
    client.write_packet(0, &out).await.unwrap();
    let mut out = Vec::new();
    put_string(&mut out, "Alex");
    out.extend_from_slice(&0u128.to_be_bytes());
    client.write_packet(0, &out).await.unwrap();

    let (stream, _) = a.game.accept().await.unwrap();
    let mut conn = Conn::new(stream, mc::MAX_FRAME);
    let (uuid, token, logged_in) = accept_login(&mut conn).await;
    assert_eq!(uuid, offline_uuid("Alex"));
    assert_eq!(token, None, "an ordinary login presents no token");
    assert_eq!(read(&mut client).await.0, 0x03);
    client.set_compression(THRESHOLD);
    assert_eq!(read(&mut client).await.0, 0x02);
    for msg in [
        Message::PlayerJoin {
            uuid,
            name: "Alex".into(),
        },
        Message::PlayerAt {
            uuid,
            chunk: ChunkKey { dim: 0, x: 0, z: 0 },
        },
    ] {
        tell(&mut a.star, msg).await;
    }
    let mut on_a = serve_player(conn, 1, logged_in, false);
    client.write_packet(0x10, b"before").await.unwrap();
    assert_eq!(
        read(&mut client).await,
        (0x11, Bytes::from_static(b"before"))
    );

    let mut sent = 0;
    let mut on_b = move_player(uuid, &mut client, &mut a, &mut on_a, &mut b, 2, &mut sent).await;
    hear_move(&mut client, 1, 2, 0, sent).await;

    // And on again: the packets the proxy counts are those of the new node.
    let first = sent;
    let mut on_c = move_player(uuid, &mut client, &mut b, &mut on_b, &mut c, 3, &mut sent).await;
    hear_move(&mut client, 2, 3, first, sent).await;

    client.write_packet(0x30, b"after").await.unwrap();
    assert_eq!(
        read(&mut client).await,
        (0x33, Bytes::from_static(b"after"))
    );
    drop(client);
    tokio::time::timeout(Duration::from_secs(5), &mut on_c.task)
        .await
        .expect("the last node hears the client leave")
        .unwrap();
    on_c.cut.closed().await;
}
