//! A cluster of real programs on loopback: lodestar, Fabric nodes running
//! Lodecore, and lodeproxy, each started in a directory of its own.
//!
//! A cluster's directory is `target/e2e/runs/<run>/<cluster>/`. It keeps every
//! program's log, the nodes' worlds and lodestar's data until the next run,
//! for looking into a failure.

use std::fs;
use std::io;
use std::net::{SocketAddr, TcpListener};
use std::path::{Path, PathBuf};
use std::process::{Command, ExitStatus};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{Context, bail};

use crate::client::Client;
use crate::process::Process;
use crate::rcon::Rcon;
use crate::setup::{Toolchain, toolchain};

const TOKEN: &str = "e2e-token";
const FORWARDING_SECRET: &str = "e2e-forwarding-secret";
const RCON_PASSWORD: &str = "e2e";
/// The world every node generates, so that their terrain agrees.
const SEED: &str = "-849452419";

#[derive(Debug, Clone)]
pub struct Options {
    pub nodes: usize,
    /// Whether lodestar moves players between nodes on its own.
    pub auto_handoff: bool,
    /// Every node's `network-compression-threshold`.
    pub compression: i32,
    pub max_players: u32,
    pub proxy: bool,
}

impl Default for Options {
    fn default() -> Self {
        Options {
            nodes: 2,
            auto_handoff: false,
            compression: 256,
            max_players: 20,
            proxy: true,
        }
    }
}

pub struct Cluster {
    pub name: String,
    pub dir: PathBuf,
    options: Options,
    tools: &'static Toolchain,
    star_port: u16,
    star: Mutex<Option<Arc<Process>>>,
    nodes: Mutex<Vec<Arc<Node>>>,
    proxy: Mutex<Option<Arc<Proxy>>>,
    plain_servers: Mutex<Vec<Process>>,
    /// How many programs have been started, to name their logs.
    launches: AtomicU32,
}

/// This run's directory, made on first use. Earlier runs' directories go then.
fn run_dir(tools: &Toolchain) -> &'static Path {
    static DIR: OnceLock<PathBuf> = OnceLock::new();
    DIR.get_or_init(|| {
        let runs = tools.work.join("runs");
        if let Ok(entries) = fs::read_dir(&runs) {
            for entry in entries.filter_map(Result::ok) {
                // A directory still in use by another run cannot be removed; let it be.
                let _ = fs::remove_dir_all(entry.path());
            }
        }
        let started = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs();
        let dir = runs.join(format!("{started}-{}", std::process::id()));
        fs::create_dir_all(&dir).expect("creating the run directory");
        dir
    })
}

fn free_port() -> u16 {
    TcpListener::bind("127.0.0.1:0")
        .and_then(|l| l.local_addr())
        .map(|a| a.port())
        .expect("finding a free port")
}

fn fail(e: anyhow::Error) -> ! {
    panic!("{e:#}")
}

impl Cluster {
    /// A cluster with nothing running yet.
    pub fn new(name: &str, options: Options) -> Cluster {
        let tools = toolchain();
        let dir = run_dir(tools).join(name);
        fs::create_dir_all(&dir).expect("creating the cluster directory");
        let star_port = free_port();
        let config = format!(
            "bind = \"127.0.0.1:{star_port}\"\ntoken = \"{TOKEN}\"\ndata_dir = \"data\"\n\
             auto_handoff = {}\n# Debug builds on a busy machine are slow to load a world.\n\
             tick_timeout_secs = 60\n",
            options.auto_handoff
        );
        fs::write(dir.join("lodestar.toml"), config).expect("writing lodestar.toml");
        Cluster {
            name: name.to_owned(),
            dir,
            options,
            tools,
            star_port,
            star: Mutex::new(None),
            nodes: Mutex::new(Vec::new()),
            proxy: Mutex::new(None),
            plain_servers: Mutex::new(Vec::new()),
            launches: AtomicU32::new(0),
        }
    }

    /// Starts lodestar, the nodes all at once, and the proxy.
    pub fn start(name: &str, options: Options) -> Cluster {
        let cluster = Cluster::new(name, options.clone());
        eprintln!("e2e: starting cluster {name} in {}", cluster.dir.display());
        let started = Instant::now();
        cluster.start_star();
        let names: Vec<String> = (1..=options.nodes).map(|i| format!("node-{i}")).collect();
        let names: Vec<&str> = names.iter().map(String::as_str).collect();
        cluster.add_nodes(&names);
        if options.proxy {
            cluster.start_proxy();
        }
        eprintln!("e2e: cluster {name} is up after {:.0?}", started.elapsed());
        cluster
    }

    fn log_name(&self, name: &str) -> PathBuf {
        let n = self.launches.fetch_add(1, Ordering::Relaxed) + 1;
        self.dir.join(format!("{n:02}-{name}.log"))
    }

    /// Starts lodestar, or starts it again, on the same data.
    pub fn start_star(&self) {
        let mut command = Command::new(&self.tools.lodestar);
        command.current_dir(&self.dir).arg("lodestar.toml");
        if std::env::var_os("RUST_LOG").is_none() {
            command.env("RUST_LOG", "info");
        }
        let process = Process::spawn("lodestar", command, &self.log_name("lodestar"))
            .unwrap_or_else(|e| fail(e));
        process
            .wait_for_log("lodestar listening", Duration::from_secs(60))
            .unwrap_or_else(|e| fail(e));
        *self.star.lock().unwrap() = Some(Arc::new(process));
    }

    /// How lodestar exited, waiting up to `timeout` for it to.
    pub fn wait_star_exit(&self, timeout: Duration) -> Option<ExitStatus> {
        let star = self.star.lock().unwrap().clone()?;
        star.wait(timeout)
    }

    pub fn star_log(&self) -> String {
        self.star.lock().unwrap().as_ref().map(|p| p.log_text()).unwrap_or_default()
    }

    pub fn kill_star(&self) {
        if let Some(star) = self.star.lock().unwrap().take() {
            star.kill();
        }
    }

    /// Starts a node, and waits until it has joined the cluster.
    pub fn add_node(&self, name: &str) -> Arc<Node> {
        self.add_node_with(name, self.options.max_players)
    }

    pub fn add_node_with(&self, name: &str, max_players: u32) -> Arc<Node> {
        self.add_set_up_node(name, Setup { max_players, ..self.setup() })
    }

    /// Starts a node that tells the proxy to send its players to another
    /// server, at `port`, and waits until it has joined the cluster.
    pub fn add_node_sending_players_to(&self, name: &str, port: u16) -> Arc<Node> {
        self.add_set_up_node(name, Setup { advertised_port: Some(port), ..self.setup() })
    }

    fn add_set_up_node(&self, name: &str, setup: Setup) -> Arc<Node> {
        let pending = self.launch_node(name, setup).unwrap_or_else(|e| fail(e));
        let node = pending.ready().unwrap_or_else(|e| fail(e));
        self.nodes.lock().unwrap().push(node.clone());
        node
    }

    /// Starts nodes side by side, and waits until all of them have joined.
    pub fn add_nodes(&self, names: &[&str]) -> Vec<Arc<Node>> {
        let pending: Vec<PendingNode> = names
            .iter()
            .map(|name| self.launch_node(name, self.setup()).unwrap_or_else(|e| fail(e)))
            .collect();
        let mut ready = Vec::new();
        for node in pending {
            ready.push(node.ready().unwrap_or_else(|e| fail(e)));
        }
        self.nodes.lock().unwrap().extend(ready.iter().cloned());
        ready
    }

    /// Starts a node with the wrong token, waits for it to give up, and returns
    /// the line in which it logged that lodestar turned it away.
    pub fn refused_node(&self, name: &str, token: &str) -> String {
        let process = self
            .launch_node(name, Setup { token, ..self.setup() })
            .unwrap_or_else(|e| fail(e))
            .process;
        if process.wait(Duration::from_secs(300)).is_none() {
            process.kill();
            panic!("{name} kept running with the wrong token; its log ends:\n{}", process.log_tail(30));
        }
        match process.log_text().lines().find(|l| l.contains("rejected")) {
            Some(line) => line.to_owned(),
            None => panic!("{name} stopped without logging that it was turned away:\n{}", process.log_tail(30)),
        }
    }

    /// Starts a Fabric server without Lodecore, which knows nothing of the
    /// cluster and takes anyone's word for who they are, and returns its game
    /// port once it is up. It runs until the cluster is shut down.
    pub fn start_plain_server(&self, name: &str) -> u16 {
        let pending = self.launch_node(name, Setup { lodecore: false, ..self.setup() }).unwrap_or_else(|e| fail(e));
        pending
            .process
            .wait_for_log("Done (", Duration::from_secs(300))
            .unwrap_or_else(|e| fail(e));
        let port = pending.game_port;
        self.plain_servers.lock().unwrap().push(pending.process);
        port
    }

    /// How a node is set up when nothing else is asked for.
    fn setup(&self) -> Setup<'static> {
        Setup {
            max_players: self.options.max_players,
            token: TOKEN,
            lodecore: true,
            advertised_port: None,
        }
    }

    fn launch_node(&self, name: &str, setup: Setup) -> anyhow::Result<PendingNode> {
        let tools = self.tools;
        let dir = self.dir.join(name);
        if dir.exists() {
            bail!("{} exists already: nodes start with an empty directory", dir.display());
        }
        fs::create_dir_all(dir.join("mods"))?;
        fs::create_dir_all(dir.join("config"))?;
        for part in [".fabric/server", "libraries", "versions"] {
            link_tree(&tools.server.join(part), &dir.join(part))?;
        }

        let game_port = free_port();
        let rcon_port = free_port();
        let max_players = setup.max_players;
        if setup.lodecore {
            link_or_copy(&tools.server.join("fabric-api.jar"), &dir.join("mods/fabric-api.jar"))?;
            fs::copy(&tools.mod_jar, dir.join("mods/lodecore.jar"))?;
            fs::write(
                dir.join("config/lodecore.properties"),
                format!(
                    "lodestar=127.0.0.1:{}\ntoken={}\nforwarding-secret={FORWARDING_SECRET}\n\
                     node-name={name}\nadvertised-address=127.0.0.1:{}\n",
                    self.star_port,
                    setup.token,
                    setup.advertised_port.unwrap_or(game_port)
                ),
            )?;
        }
        fs::write(dir.join("eula.txt"), "eula=true\n")?;
        fs::write(
            dir.join("server.properties"),
            format!(
                "server-ip=127.0.0.1\nserver-port={game_port}\nmotd={name}\n\
                 enable-rcon=true\nrcon.port={rcon_port}\nrcon.password={RCON_PASSWORD}\n\
                 online-mode=false\nenforce-secure-profile=false\nwhite-list=false\n\
                 max-players={max_players}\nnetwork-compression-threshold={}\n\
                 level-seed={SEED}\nview-distance=4\nsimulation-distance=4\nspawn-protection=0\n\
                 pause-when-empty-seconds=-1\nmax-tick-time=120000\nbroadcast-rcon-to-ops=false\n",
                self.options.compression
            ),
        )?;

        let mut command = Command::new(&tools.java);
        command
            .current_dir(&dir)
            .args(["-Xmx1536M", "-jar"])
            .arg(tools.server.join("fabric-server.jar"))
            .arg("nogui");
        let process = Process::spawn(name, command, &self.log_name(name))?;
        Ok(PendingNode {
            name: name.to_owned(),
            dir,
            game_port,
            rcon_port,
            process,
        })
    }

    /// The nodes started so far, stopped ones included.
    pub fn nodes(&self) -> Vec<Arc<Node>> {
        self.nodes.lock().unwrap().clone()
    }

    /// The nodes that are running.
    pub fn live_nodes(&self) -> Vec<Arc<Node>> {
        self.nodes().into_iter().filter(|n| n.exit_status().is_none()).collect()
    }

    pub fn node_by_id(&self, id: u32) -> Arc<Node> {
        self.live_nodes()
            .into_iter()
            .find(|n| n.id == id)
            .unwrap_or_else(|| panic!("no running node #{id}"))
    }

    /// Starts lodeproxy, or starts it again.
    pub fn start_proxy(&self) {
        let port = free_port();
        let process = self.launch_proxy(port, "lodeproxy.toml", TOKEN);
        for line in ["lodeproxy listening", "connected to lodestar"] {
            process
                .wait_for_log(line, Duration::from_secs(60))
                .unwrap_or_else(|e| fail(e));
        }
        *self.proxy.lock().unwrap() = Some(Arc::new(Proxy {
            addr: SocketAddr::from(([127, 0, 0, 1], port)),
            process,
        }));
    }

    /// Starts a second lodeproxy with the wrong token, and returns the line in
    /// which it logged why lodestar turned it away. It is ended then.
    pub fn refused_proxy(&self, token: &str) -> String {
        let process = self.launch_proxy(free_port(), "lodeproxy-refused.toml", token);
        let line = process
            .wait_for_log("lodestar rejected us", Duration::from_secs(60))
            .unwrap_or_else(|e| fail(e));
        process.kill();
        line
    }

    fn launch_proxy(&self, port: u16, config_name: &str, token: &str) -> Process {
        let config = format!(
            "bind = \"127.0.0.1:{port}\"\nlodestar = \"127.0.0.1:{}\"\ntoken = \"{token}\"\n\
             forwarding_secret = \"{FORWARDING_SECRET}\"\nonline_mode = false\nmotd = \"Lodestar E2E\"\n",
            self.star_port
        );
        fs::write(self.dir.join(config_name), config).expect("writing lodeproxy's config");
        let mut command = Command::new(&self.tools.lodeproxy);
        command.current_dir(&self.dir).arg(config_name);
        if std::env::var_os("RUST_LOG").is_none() {
            command.env("RUST_LOG", "info");
        }
        Process::spawn("lodeproxy", command, &self.log_name("lodeproxy")).unwrap_or_else(|e| fail(e))
    }

    pub fn stop_proxy(&self) {
        if let Some(proxy) = self.proxy.lock().unwrap().take() {
            proxy.process.kill();
        }
    }

    /// Where players connect.
    pub fn proxy_addr(&self) -> SocketAddr {
        self.proxy.lock().unwrap().as_ref().expect("the proxy is not running").addr
    }

    pub fn proxy_log(&self) -> String {
        self.proxy.lock().unwrap().as_ref().map(|p| p.process.log_text()).unwrap_or_default()
    }

    /// Logs a scripted player in through the proxy, and waits until it is in
    /// the world and on a node's player list.
    pub fn join(&self, name: &str) -> Client {
        let client = Client::connect(self.proxy_addr(), name).unwrap_or_else(|e| fail(e));
        client.wait_in_world(Duration::from_secs(90));
        crate::wait_for(&format!("{name} to be on a node's player list"), Duration::from_secs(30), || {
            self.home_of(name).map(|_| ()).ok_or_else(|| "on no node".to_owned())
        });
        client
    }

    /// The node a player is homed on: the one that lists them.
    pub fn home_of(&self, player: &str) -> Option<Arc<Node>> {
        self.live_nodes().into_iter().find(|n| n.players().iter().any(|p| p == player))
    }

    /// Ends every program, as abruptly as a power cut.
    pub fn shutdown(&self) {
        self.stop_proxy();
        for node in self.nodes() {
            node.kill();
        }
        for server in self.plain_servers.lock().unwrap().iter() {
            server.kill();
        }
        self.kill_star();
    }
}

impl Drop for Cluster {
    fn drop(&mut self) {
        self.shutdown();
    }
}

/// Mirrors a directory with hard links to its files, which costs no space and
/// no time; or with copies, where links cannot be made.
fn link_tree(from: &Path, to: &Path) -> anyhow::Result<()> {
    fs::create_dir_all(to)?;
    for entry in fs::read_dir(from).with_context(|| format!("reading {}", from.display()))? {
        let entry = entry?;
        let target = to.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            link_tree(&entry.path(), &target)?;
        } else {
            link_or_copy(&entry.path(), &target)?;
        }
    }
    Ok(())
}

fn link_or_copy(from: &Path, to: &Path) -> anyhow::Result<()> {
    if fs::hard_link(from, to).is_err() {
        fs::copy(from, to).with_context(|| format!("copying {} to {}", from.display(), to.display()))?;
    }
    Ok(())
}

/// How a server is set up to start.
struct Setup<'a> {
    max_players: u32,
    token: &'a str,
    /// Whether it runs Lodecore. One that does not is a plain server, which
    /// knows nothing of the cluster.
    lodecore: bool,
    /// Where it tells the proxy to send its players, if not to itself.
    advertised_port: Option<u16>,
}

struct PendingNode {
    name: String,
    dir: PathBuf,
    game_port: u16,
    rcon_port: u16,
    process: Process,
}

impl PendingNode {
    /// Waits until the node has joined the cluster and taken its first command.
    fn ready(self) -> anyhow::Result<Arc<Node>> {
        let joined = self
            .process
            .wait_for_log("Connected to lodestar at", Duration::from_secs(300))?;
        let id = joined
            .rsplit_once("as node #")
            .and_then(|(_, n)| n.trim().parse().ok())
            .with_context(|| format!("no node number in {joined:?}"))?;
        self.process.wait_for_log("Done (", Duration::from_secs(60))?;
        let node = Node {
            name: self.name,
            id,
            dir: self.dir,
            game_port: self.game_port,
            rcon_port: self.rcon_port,
            process: self.process,
            rcon: Mutex::new(None),
        };
        let deadline = Instant::now() + Duration::from_secs(60);
        while let Err(e) = node.try_cmd("list") {
            if Instant::now() >= deadline {
                bail!("{}'s console did not answer: {e}", node.name);
            }
            std::thread::sleep(Duration::from_millis(500));
        }
        Ok(Arc::new(node))
    }
}

pub struct Proxy {
    pub addr: SocketAddr,
    process: Process,
}

/// A Fabric server running Lodecore, which has joined the cluster.
pub struct Node {
    pub name: String,
    /// The number lodestar gave it.
    pub id: u32,
    pub dir: PathBuf,
    pub game_port: u16,
    rcon_port: u16,
    process: Process,
    rcon: Mutex<Option<Rcon>>,
}

impl std::fmt::Debug for Node {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{} (#{})", self.name, self.id)
    }
}

impl Node {
    /// Runs a console command and returns its answer. Panics if the console
    /// cannot be reached.
    pub fn cmd(&self, command: &str) -> String {
        self.try_cmd(command).unwrap_or_else(|e| {
            panic!(
                "{self:?} could not run `{command}`: {e}; its log ends:\n{}",
                self.process.log_tail(30)
            )
        })
    }

    pub fn try_cmd(&self, command: &str) -> io::Result<String> {
        let mut rcon = self.rcon.lock().unwrap_or_else(|e| e.into_inner());
        if rcon.is_none() {
            *rcon = Some(Rcon::connect(self.rcon_port, RCON_PASSWORD)?);
        }
        let result = rcon.as_mut().unwrap().run(command);
        if result.is_err() {
            *rcon = None;
        }
        result
    }

    /// Runs commands one after another, for setting a stage.
    pub fn cmds(&self, commands: &[&str]) {
        for command in commands {
            self.cmd(command);
        }
    }

    /// Whether `execute if <condition>` passes.
    pub fn passes(&self, condition: &str) -> bool {
        self.cmd(&format!("execute if {condition}")).starts_with("Test passed")
    }

    pub fn has_block(&self, x: i32, y: i32, z: i32, block: &str) -> bool {
        self.passes(&format!("block {x} {y} {z} {block}"))
    }

    /// How many entities a selector finds here.
    pub fn count(&self, selector: &str) -> usize {
        let reply = self.cmd(&format!("execute if entity {selector}"));
        if !reply.starts_with("Test passed") {
            return 0;
        }
        int_after(&reply, "Count: ").map_or(1, |n| n as usize)
    }

    /// The players homed here.
    pub fn players(&self) -> Vec<String> {
        let reply = self.cmd("list");
        let names = reply.split_once(':').map_or("", |(_, names)| names);
        names
            .split(',')
            .map(str::trim)
            .filter(|n| !n.is_empty())
            .map(str::to_owned)
            .collect()
    }

    pub fn has_player(&self, name: &str) -> bool {
        self.players().iter().any(|p| p == name)
    }

    /// The node that simulates the column at x, z, as this node knows it.
    pub fn owner_of(&self, x: i32, z: i32) -> Option<u32> {
        let reply = self.cmd(&format!("lodecore owner {x} {z}"));
        let (_, rest) = reply.split_once('#')?;
        rest.split(|c: char| !c.is_ascii_digit()).next()?.parse().ok()
    }

    /// The entity id `/lodecore id` gives for a selector.
    pub fn entity_id(&self, selector: &str) -> Option<i32> {
        let reply = self.cmd(&format!("lodecore id {selector}"));
        reply.rsplit_once("has id ")?.1.trim().parse().ok()
    }

    pub fn gametime(&self) -> i64 {
        let reply = self.cmd("time query gametime");
        int_after(&reply, "is ")
            .unwrap_or_else(|| panic!("{self:?} answered `time query gametime` with {reply:?}"))
    }

    /// The number a `data get` answers with, such as `20.0f` or `3.5d`.
    pub fn number(&self, command: &str) -> Option<f64> {
        nbt_number(&self.cmd(command))
    }

    pub fn log(&self) -> String {
        self.process.log_text()
    }

    pub fn exit_status(&self) -> Option<ExitStatus> {
        self.process.exit_status()
    }

    pub fn wait_exit(&self, timeout: Duration) -> Option<ExitStatus> {
        self.process.wait(timeout)
    }

    /// Stops the server as an operator would, and waits until it has.
    pub fn stop(&self) -> ExitStatus {
        let _ = self.try_cmd("stop");
        self.process
            .wait(Duration::from_secs(120))
            .unwrap_or_else(|| panic!("{self:?} did not stop within 2 minutes"))
    }

    /// Ends the server at once, as a crash would.
    pub fn kill(&self) {
        self.process.kill();
    }
}

/// The whole number that follows the first `marker` in a reply.
pub fn int_after(reply: &str, marker: &str) -> Option<i64> {
    let (_, rest) = reply.split_once(marker)?;
    let digits: String = rest
        .chars()
        .enumerate()
        .take_while(|(i, c)| c.is_ascii_digit() || (*i == 0 && *c == '-'))
        .map(|(_, c)| c)
        .collect();
    digits.parse().ok()
}

/// How many of an item an NBT list of item stacks, as `data get` shows it,
/// holds in all.
pub fn item_count(reply: &str, item: &str) -> u32 {
    let id = format!("id: \"minecraft:{item}\"");
    reply
        .split('{')
        .filter_map(|part| part.split('}').next())
        .filter(|stack| stack.contains(&id))
        .filter_map(|stack| int_after(stack, "count: "))
        .map(|n| n as u32)
        .sum()
}

/// The number after the last colon of an answer, without its NBT suffix.
pub fn nbt_number(reply: &str) -> Option<f64> {
    let (_, value) = reply.rsplit_once(": ")?;
    value
        .trim()
        .trim_end_matches(['f', 'd', 'b', 's', 'L'])
        .parse()
        .ok()
}
