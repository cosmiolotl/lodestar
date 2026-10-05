//! Runs a real lodestar and a real proxy over loopback, with a scripted node
//! and a scripted game client on either side.

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use bytes::Bytes;
use hmac::{Hmac, Mac};
use lode_protocol::{Message, PROTOCOL_VERSION, Role, read_message, write_message};
use lodeproxy::auth::offline_uuid;
use lodeproxy::config::Config;
use lodeproxy::mc::{self, Conn, get_string, get_u128, get_varint, put_string, put_varint};
use lodeproxy::session::{self, Proxy};
use lodestar::state::Policy;
use sha2::Sha256;
use tokio::net::{TcpListener, TcpStream};

const TOKEN: &str = "cluster-token";
const SECRET: &str = "forwarding-secret";
const PROTOCOL: i32 = 775;

struct Cluster {
    star: SocketAddr,
    proxy: SocketAddr,
    proxy_handle: Arc<Proxy>,
}

async fn cluster() -> Cluster {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let star = listener.local_addr().unwrap();
    let policy = Policy {
        token: TOKEN.into(),
        heartbeat_timeout: Duration::from_secs(10),
        reservation_ttl: Duration::from_secs(30),
        mspt_limit: Duration::from_millis(45),
        tick_interval: Duration::from_millis(50),
        // The scripted nodes here never answer a tick.
        tick_timeout: Duration::from_secs(600),
        auto_handoff: false,
    };
    tokio::spawn(lodestar::server::serve(listener, policy, None));

    let config = Config {
        lodestar: star.to_string(),
        token: TOKEN.into(),
        forwarding_secret: SECRET.into(),
        online_mode: false,
        motd: "test cluster".into(),
        ..Config::default()
    };
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let proxy = listener.local_addr().unwrap();
    let proxy_handle = Arc::new(Proxy::new(config).unwrap());
    tokio::spawn(session::serve(listener, proxy_handle.clone()));

    // Wait for the proxy's background connection to lodestar.
    while proxy_handle.star.status().await.is_none() {
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    Cluster {
        star,
        proxy,
        proxy_handle,
    }
}

/// Registers a node with lodestar. The connection must be kept alive.
async fn register_node(star: SocketAddr, game_addr: SocketAddr) -> TcpStream {
    let mut stream = TcpStream::connect(star).await.unwrap();
    let hello = Message::Hello {
        protocol: PROTOCOL_VERSION,
        token: TOKEN.into(),
        name: "node".into(),
        role: Role::Node {
            game_addr: game_addr.to_string(),
            max_players: 50,
        },
    };
    write_message(&mut stream, &hello).await.unwrap();
    assert!(matches!(
        read_message(&mut stream).await,
        Ok(Some(Message::Welcome { .. }))
    ));
    stream
}

/// A node's game port: logs one player in, then echoes packets with the id bumped.
async fn fake_node(listener: TcpListener, ask_for_identity: bool) {
    let (stream, _) = listener.accept().await.unwrap();
    let mut conn = Conn::new(stream, mc::MAX_FRAME);

    let (id, mut handshake) = conn.read_packet().await.unwrap();
    assert_eq!(id, 0);
    assert_eq!(get_varint(&mut handshake).unwrap(), PROTOCOL);

    let (id, mut start) = conn.read_packet().await.unwrap();
    assert_eq!(id, 0);
    let name = get_string(&mut start, 16).unwrap();
    let uuid = get_u128(&mut start).unwrap();

    if ask_for_identity {
        let mut out = Vec::new();
        put_varint(&mut out, 7);
        put_string(&mut out, "velocity:player_info");
        conn.write_packet(0x04, &out).await.unwrap();

        let (id, mut answer) = conn.read_packet().await.unwrap();
        assert_eq!(id, 0x02);
        assert_eq!(get_varint(&mut answer).unwrap(), 7);
        assert_eq!(answer.split_to(1)[0], 1, "proxy understood the request");
        let signature = answer.split_to(32);
        let mut mac = Hmac::<Sha256>::new_from_slice(SECRET.as_bytes()).unwrap();
        mac.update(&answer);
        mac.verify_slice(&signature)
            .expect("identity is signed with the shared secret");
        assert_eq!(get_varint(&mut answer).unwrap(), 1);
        assert_eq!(get_string(&mut answer, 64).unwrap(), "127.0.0.1");
        assert_eq!(get_u128(&mut answer).unwrap(), uuid);
        assert_eq!(get_string(&mut answer, 16).unwrap(), name);
    }

    let mut out = Vec::new();
    put_varint(&mut out, 64);
    conn.write_packet(0x03, &out).await.unwrap();
    conn.set_compression(64);

    let mut out = uuid.to_be_bytes().to_vec();
    put_string(&mut out, &name);
    put_varint(&mut out, 0);
    conn.write_packet(0x02, &out).await.unwrap();

    while let Ok((id, payload)) = conn.read_packet().await {
        conn.write_packet(id + 1, &payload).await.unwrap();
    }
}

async fn connect(proxy: SocketAddr, intent: i32) -> Conn {
    let mut conn = Conn::new(TcpStream::connect(proxy).await.unwrap(), mc::MAX_FRAME);
    let mut out = Vec::new();
    put_varint(&mut out, PROTOCOL);
    put_string(&mut out, "play.example.com");
    out.extend_from_slice(&25565u16.to_be_bytes());
    put_varint(&mut out, intent);
    conn.write_packet(0, &out).await.unwrap();
    conn
}

async fn start_login(proxy: SocketAddr, name: &str) -> Conn {
    let mut conn = connect(proxy, 2).await;
    let mut out = Vec::new();
    put_string(&mut out, name);
    out.extend_from_slice(&0u128.to_be_bytes());
    conn.write_packet(0, &out).await.unwrap();
    conn
}

async fn read(conn: &mut Conn) -> (i32, Bytes) {
    tokio::time::timeout(Duration::from_secs(5), conn.read_packet())
        .await
        .expect("timed out waiting for a packet")
        .unwrap()
}

/// Reads a login disconnect and returns its reason.
async fn read_refusal(conn: &mut Conn) -> String {
    let (id, mut packet) = read(conn).await;
    assert_eq!(id, 0x00, "expected a disconnect");
    let json: serde_json::Value =
        serde_json::from_str(&get_string(&mut packet, 32767).unwrap()).unwrap();
    json["text"].as_str().unwrap().to_owned()
}

#[tokio::test]
async fn player_is_logged_in_and_relayed_to_a_node() {
    let cluster = cluster().await;
    let game = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let _node = register_node(cluster.star, game.local_addr().unwrap()).await;
    let node = tokio::spawn(fake_node(game, true));

    let mut client = start_login(cluster.proxy, "Alex").await;

    let (id, mut packet) = read(&mut client).await;
    assert_eq!(
        id, 0x03,
        "the node's compression setting reaches the client"
    );
    client.set_compression(get_varint(&mut packet).unwrap());

    let (id, mut packet) = read(&mut client).await;
    assert_eq!(id, 0x02);
    assert_eq!(get_u128(&mut packet).unwrap(), offline_uuid("Alex"));
    assert_eq!(get_string(&mut packet, 16).unwrap(), "Alex");

    // From here on the proxy passes bytes through; small and compressed packets both survive.
    for payload in [vec![1u8; 8], vec![2u8; 4000]] {
        client.write_packet(0x20, &payload).await.unwrap();
        assert_eq!(read(&mut client).await, (0x21, Bytes::from(payload)));
    }

    drop(client);
    tokio::time::timeout(Duration::from_secs(5), node)
        .await
        .expect("the node's connection closes when the player leaves")
        .unwrap();
}

#[tokio::test]
async fn status_reports_cluster_capacity() {
    let cluster = cluster().await;
    let game = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let _node = register_node(cluster.star, game.local_addr().unwrap()).await;
    // Let the cached, empty status from startup expire.
    tokio::time::sleep(Duration::from_millis(1100)).await;

    let mut client = connect(cluster.proxy, 1).await;
    client.write_packet(0, &[]).await.unwrap();
    let (id, mut packet) = read(&mut client).await;
    assert_eq!(id, 0);
    let json: serde_json::Value =
        serde_json::from_str(&get_string(&mut packet, 32767).unwrap()).unwrap();
    assert_eq!(json["players"]["max"], 50);
    assert_eq!(json["players"]["online"], 0);
    assert_eq!(json["version"]["protocol"], PROTOCOL);
    assert_eq!(json["description"]["text"], "test cluster");

    client.write_packet(1, &42i64.to_be_bytes()).await.unwrap();
    assert_eq!(
        read(&mut client).await,
        (1, Bytes::copy_from_slice(&42i64.to_be_bytes()))
    );
    let _ = cluster.proxy_handle;
}

#[tokio::test]
async fn player_is_told_when_no_node_can_take_them() {
    let cluster = cluster().await;
    let mut client = start_login(cluster.proxy, "Alex").await;
    assert_eq!(
        read_refusal(&mut client).await,
        "No servers are online right now."
    );
}

#[tokio::test]
async fn node_that_skips_identity_forwarding_is_not_trusted() {
    let cluster = cluster().await;
    let game = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let _node = register_node(cluster.star, game.local_addr().unwrap()).await;
    tokio::spawn(fake_node(game, false));

    let mut client = start_login(cluster.proxy, "Alex").await;
    // The compression switch is relayed before the node's mistake shows.
    let (id, mut packet) = read(&mut client).await;
    assert_eq!(id, 0x03);
    client.set_compression(get_varint(&mut packet).unwrap());
    assert_eq!(
        read_refusal(&mut client).await,
        "Could not reach the server. Try again in a moment."
    );
}
