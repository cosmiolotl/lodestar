//! What a cluster is made of, built or found once per run: a Java runtime,
//! `lodestar` and `lodeproxy`, the Lodecore jar, and a Fabric server
//! installation that each node's directory is made from.
//!
//! - Java: `LODESTAR_E2E_JAVA_HOME`, else `JAVA_HOME`, else the newest
//!   `.tools/jdk-*` in the repository. It must be Java 25 or newer.
//! - `lodestar` and `lodeproxy`: built with Cargo, in the profile the tests
//!   were built in.
//! - Lodecore: `mod/build/libs/lodecore-<version>.jar`, rebuilt with Gradle
//!   when anything in `mod/` is newer, unless `LODESTAR_E2E_MOD_JAR` names a
//!   jar to use as it is.
//! - Fabric: the versions in `mod/gradle.properties`, downloaded on first use
//!   to `target/e2e/server-*`, with the Minecraft server and libraries the
//!   Fabric launcher fetches.

use std::fs;
use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::OnceLock;
use std::time::{Duration, Instant, SystemTime};

use anyhow::{Context, bail, ensure};

/// The Fabric installer whose server launcher starts the nodes.
const FABRIC_INSTALLER: &str = "1.1.2";

pub struct Toolchain {
    pub root: PathBuf,
    /// `target/e2e`: the Fabric installation and each run's clusters.
    pub work: PathBuf,
    pub java_home: PathBuf,
    pub java: PathBuf,
    pub lodestar: PathBuf,
    pub lodeproxy: PathBuf,
    pub mod_jar: PathBuf,
    /// A Fabric server installation, with Fabric API, that has run once.
    pub server: PathBuf,
    pub minecraft: String,
}

/// The toolchain, prepared on first use. Panics with what is missing if it
/// cannot be, which fails the test that asked first, and every one after it.
pub fn toolchain() -> &'static Toolchain {
    static TOOLCHAIN: OnceLock<Result<Toolchain, String>> = OnceLock::new();
    match TOOLCHAIN.get_or_init(|| prepare().map_err(|e| format!("{e:#}"))) {
        Ok(toolchain) => toolchain,
        Err(e) => panic!("cannot set up the end-to-end tests: {e}"),
    }
}

fn prepare() -> anyhow::Result<Toolchain> {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .context("the tests crate has no parent directory")?
        .to_path_buf();
    // This program is <target>/<profile>/deps/e2e-<hash>.
    let exe = std::env::current_exe()?;
    let profile_dir = exe
        .parent()
        .and_then(Path::parent)
        .context("cannot tell the target directory from the test program's path")?;
    let target = profile_dir.parent().context("no target directory")?;
    let profile = profile_dir.file_name().and_then(|n| n.to_str()).unwrap_or("debug");
    let work = target.join("e2e");
    fs::create_dir_all(work.join("logs"))?;

    let properties = gradle_properties(&root.join("mod/gradle.properties"))?;
    let get = |key: &str| {
        properties
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.clone())
            .with_context(|| format!("`{key}` is missing from mod/gradle.properties"))
    };
    let minecraft = get("minecraft_version")?;
    let loader = get("loader_version")?;
    let fabric_api = get("fabric_api_version")?;
    let mod_version = get("version")?;

    let java_home = java_home(&root)?;
    let java = java_home.join("bin").join(format!("java{}", std::env::consts::EXE_SUFFIX));
    ensure!(java.exists(), "{} does not exist", java.display());

    let (lodestar, lodeproxy) = binaries(&root, profile_dir, profile, &work)?;
    let mod_jar = mod_jar(&root, &java_home, &mod_version, &work)?;
    let server = fabric_server(&work, &java, &minecraft, &loader, &fabric_api)?;

    Ok(Toolchain {
        root,
        work,
        java_home,
        java,
        lodestar,
        lodeproxy,
        mod_jar,
        server,
        minecraft,
    })
}

fn gradle_properties(path: &Path) -> anyhow::Result<Vec<(String, String)>> {
    let text = fs::read_to_string(path).with_context(|| format!("reading {}", path.display()))?;
    Ok(text
        .lines()
        .filter(|line| !line.trim_start().starts_with('#'))
        .filter_map(|line| line.split_once('='))
        .map(|(k, v)| (k.trim().to_owned(), v.trim().to_owned()))
        .collect())
}

fn java_home(root: &Path) -> anyhow::Result<PathBuf> {
    let mut candidates: Vec<PathBuf> = ["LODESTAR_E2E_JAVA_HOME", "JAVA_HOME"]
        .iter()
        .filter_map(|var| std::env::var_os(var))
        .map(PathBuf::from)
        .collect();
    if let Ok(entries) = fs::read_dir(root.join(".tools")) {
        let mut jdks: Vec<PathBuf> = entries
            .filter_map(Result::ok)
            .map(|e| e.path())
            .filter(|p| p.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.starts_with("jdk-")))
            .collect();
        jdks.sort();
        candidates.extend(jdks.into_iter().rev());
    }
    for home in &candidates {
        if java_major(home).is_some_and(|major| major >= 25) {
            return Ok(home.clone());
        }
    }
    bail!(
        "no Java 25 found (looked at {candidates:?}); set LODESTAR_E2E_JAVA_HOME or JAVA_HOME to a JDK 25"
    )
}

/// The major version in a Java installation's `release` file.
fn java_major(home: &Path) -> Option<u32> {
    let release = fs::read_to_string(home.join("release")).ok()?;
    let line = release.lines().find(|l| l.starts_with("JAVA_VERSION="))?;
    let version = line.trim_start_matches("JAVA_VERSION=").trim_matches('"');
    version.split(['.', '+', '-']).next()?.parse().ok()
}

/// Builds `lodestar` and `lodeproxy` as they are now, which is what is under
/// test, in the profile the tests were built in.
fn binaries(
    root: &Path,
    profile_dir: &Path,
    profile: &str,
    work: &Path,
) -> anyhow::Result<(PathBuf, PathBuf)> {
    let cargo = std::env::var_os("CARGO").unwrap_or_else(|| "cargo".into());
    let mut command = Command::new(cargo);
    command.current_dir(root).args(["build", "-p", "lodestar", "-p", "lodeproxy"]);
    match profile {
        "debug" => {}
        "release" => {
            command.arg("--release");
        }
        other => {
            command.args(["--profile", other]);
        }
    }
    let log_path = work.join("logs/cargo-build.log");
    eprintln!("e2e: building lodestar and lodeproxy");
    let mut child = command
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .spawn()
        .context("running cargo")?;
    // Cargo waits for the build directory lock if the `cargo test` that ran
    // this still holds it; that would never end, so give up on it.
    let stderr = child.stderr.take().expect("piped");
    let (lines_tx, lines_rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        for line in BufReader::new(stderr).lines().map_while(Result::ok) {
            if lines_tx.send(line).is_err() {
                break;
            }
        }
    });
    let mut log = String::new();
    let deadline = Instant::now() + Duration::from_secs(900);
    let status = loop {
        while let Ok(line) = lines_rx.try_recv() {
            if line.contains("Blocking waiting for file lock on build directory") {
                let _ = child.kill();
                bail!(
                    "cargo is waiting for the lock this test run holds; build first with \
                     `cargo build -p lodestar -p lodeproxy`"
                );
            }
            log.push_str(&line);
            log.push('\n');
        }
        if let Some(status) = child.try_wait()? {
            break status;
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            bail!("building lodestar and lodeproxy took more than 15 minutes");
        }
        std::thread::sleep(Duration::from_millis(100));
    };
    while let Ok(line) = lines_rx.recv_timeout(Duration::from_millis(200)) {
        log.push_str(&line);
        log.push('\n');
    }
    fs::write(&log_path, &log)?;
    ensure!(status.success(), "building lodestar and lodeproxy failed:\n{log}");

    let exe = |name: &str| profile_dir.join(format!("{name}{}", std::env::consts::EXE_SUFFIX));
    let (lodestar, lodeproxy) = (exe("lodestar"), exe("lodeproxy"));
    ensure!(lodestar.exists() && lodeproxy.exists(), "cargo built no lodestar or lodeproxy in {}", profile_dir.display());
    Ok((lodestar, lodeproxy))
}

/// The Lodecore jar, rebuilt if any of the mod's sources are newer.
fn mod_jar(root: &Path, java_home: &Path, version: &str, work: &Path) -> anyhow::Result<PathBuf> {
    if let Some(jar) = std::env::var_os("LODESTAR_E2E_MOD_JAR") {
        let jar = PathBuf::from(jar);
        ensure!(jar.exists(), "LODESTAR_E2E_MOD_JAR is {}, which does not exist", jar.display());
        return Ok(jar);
    }
    let mod_dir = root.join("mod");
    let jar = mod_dir.join(format!("build/libs/lodecore-{version}.jar"));
    // Gradle leaves the jar alone when a change does not touch it, so the
    // last successful build counts as well as the jar's own time.
    let stamp = work.join("mod-built");
    let built = modified(&jar).filter(|_| jar.exists()).max(modified(&stamp));
    let sources = ["src", "build.gradle", "settings.gradle", "gradle.properties"]
        .iter()
        .map(|p| newest(&mod_dir.join(p)))
        .max()
        .flatten();
    if jar.exists() && built >= sources {
        return Ok(jar);
    }

    eprintln!("e2e: building the Lodecore jar");
    let gradlew = if cfg!(windows) { "gradlew.bat" } else { "gradlew" };
    let log_path = work.join("logs/gradle-build.log");
    let log = fs::File::create(&log_path)?;
    // No daemon: one would outlive the tests, holding on to their output.
    let status = Command::new(mod_dir.join(gradlew))
        .current_dir(&mod_dir)
        .args(["build", "--console=plain", "--no-daemon"])
        .env("JAVA_HOME", java_home)
        .stdout(log.try_clone()?)
        .stderr(log)
        .status()
        .context("running Gradle")?;
    ensure!(
        status.success(),
        "building the Lodecore jar failed; see {}",
        log_path.display()
    );
    ensure!(
        jar.exists(),
        "Gradle succeeded but did not write {}; see {}",
        jar.display(),
        log_path.display()
    );
    // Something to write: rewriting an empty file with nothing leaves its time alone.
    fs::write(&stamp, format!("{:?}\n", SystemTime::now()))?;
    Ok(jar)
}

fn modified(path: &Path) -> Option<SystemTime> {
    fs::metadata(path).and_then(|m| m.modified()).ok()
}

/// The newest modification time of a file, or of anything in a directory.
fn newest(path: &Path) -> Option<SystemTime> {
    let meta = fs::metadata(path).ok()?;
    if !meta.is_dir() {
        return meta.modified().ok();
    }
    fs::read_dir(path)
        .ok()?
        .filter_map(Result::ok)
        .filter_map(|entry| newest(&entry.path()))
        .max()
}

/// A Fabric server installation that has been started once, so that its
/// launcher has fetched the Minecraft server and the libraries. Nodes are made
/// from it without touching the network.
fn fabric_server(
    work: &Path,
    java: &Path,
    minecraft: &str,
    loader: &str,
    fabric_api: &str,
) -> anyhow::Result<PathBuf> {
    let dir = work.join(format!("server-{minecraft}-loader-{loader}-api-{fabric_api}").replace('+', "_"));
    let ready = dir.join("ready");
    if ready.exists() {
        return Ok(dir);
    }
    eprintln!("e2e: downloading Fabric {loader} for Minecraft {minecraft}, with Fabric API {fabric_api}");
    if dir.exists() {
        fs::remove_dir_all(&dir).with_context(|| format!("clearing {}", dir.display()))?;
    }
    fs::create_dir_all(&dir)?;
    download(
        &format!("https://meta.fabricmc.net/v2/versions/loader/{minecraft}/{loader}/{FABRIC_INSTALLER}/server/jar"),
        &dir.join("fabric-server.jar"),
    )?;
    download(
        &format!(
            "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{fabric_api}/fabric-api-{fabric_api}.jar"
        ),
        &dir.join("fabric-api.jar"),
    )?;

    // Without an accepted EULA the server stops as soon as it has unpacked itself.
    let log_path = dir.join("install.log");
    let log = fs::File::create(&log_path)?;
    let mut child = Command::new(java)
        .current_dir(&dir)
        .args(["-jar", "fabric-server.jar", "nogui"])
        .stdin(Stdio::null())
        .stdout(log.try_clone()?)
        .stderr(log)
        .spawn()?;
    let deadline = Instant::now() + Duration::from_secs(600);
    while child.try_wait()?.is_none() {
        if Instant::now() >= deadline {
            let _ = child.kill();
            bail!("the Fabric server did not install in 10 minutes; see {}", log_path.display());
        }
        std::thread::sleep(Duration::from_millis(250));
    }
    for needed in [
        dir.join(".fabric/server").join(format!("{minecraft}-server.jar")),
        dir.join("libraries"),
        dir.join("versions").join(minecraft),
    ] {
        ensure!(
            needed.exists(),
            "the Fabric server did not install {}; see {}",
            needed.display(),
            log_path.display()
        );
    }
    fs::write(ready, "")?;
    Ok(dir)
}

fn download(url: &str, to: &Path) -> anyhow::Result<()> {
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build()?;
    let bytes = runtime.block_on(async {
        let response = reqwest::get(url).await?.error_for_status()?;
        response.bytes().await
    });
    let bytes = bytes.with_context(|| format!("downloading {url}"))?;
    let partial = to.with_extension("part");
    fs::write(&partial, &bytes)?;
    fs::rename(&partial, to)?;
    Ok(())
}
