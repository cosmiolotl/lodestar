# End-to-end tests

Each test runs a real cluster on this machine: the `lodestar` and `lodeproxy`
programs, and Fabric servers running the Lodecore jar. It drives the nodes
through RCON, as an operator would, and logs in scripted players through the
proxy, as game clients would. Nothing is faked or called in process.

```sh
cargo test -p lodestar-e2e                 # everything, about a quarter of an hour
cargo test -p lodestar-e2e -- handoff      # tests with "handoff" in their name
cargo test -p lodestar-e2e -- --list
```

## What it needs

- Rust, and a JDK 25: `LODESTAR_E2E_JAVA_HOME`, else `JAVA_HOME`, else the
  newest `.tools/jdk-*` in the repository.
- The network, the first time: the Fabric server, Fabric API and the Minecraft
  server are downloaded once to `target/e2e/server-*`, in the versions
  `mod/gradle.properties` names. The test servers are started with
  `eula=true`: running the tests accepts the
  [Minecraft EULA](https://aka.ms/MinecraftEULA).
- About 4 GB of free memory: two nodes run at a time.

The tests build what they test: `lodestar` and `lodeproxy` with Cargo, and
the Lodecore jar with Gradle when anything in `mod/` is newer than it. Set
`LODESTAR_E2E_MOD_JAR` to test another jar as it is.

## Looking into a failure

Every cluster runs in `target/e2e/runs/<run>/<cluster>/`, which keeps the logs
of every program it started (`01-lodestar.log`, `02-node-1.log`, ...), each
node's directory and world, and lodestar's data, until the next run. Set
`RUST_LOG` to change what lodestar and lodeproxy log (`info` by default).

On Windows the programs a run starts end with it, however it ends. Elsewhere,
an interrupted run can leave them running.

## How the tests are laid out

- `harness/`: starting clusters (`cluster.rs`), the scripted player
  (`client.rs`), RCON, and building or downloading what a cluster needs
  (`setup.rs`).
- `suite/`: the tests, one file per part of the system, and `main.rs`, which
  lists them.

Most tests share one two-node cluster, started by the first of them. Each
works in a part of the world of its own, far enough from the others to be a
zone of its own, with players of its own names, and puts back anything it
changes for the whole cluster, such as the difficulty or `/tick`. Tests that
restart or crash parts of a cluster, or need one set up differently, start
their own once the shared one has been stopped. Tests run one at a time.

The scripted player answers what a client must (keep-alives, teleports, chunk
batches, respawning), walks, chats, attacks, and places and breaks blocks. It
keeps what a player would notice: where the server put it, the entities and
chunks it was shown and anything out of place about them, the messages it was
sent, and why its connection ended. Its packet ids are Minecraft 26.3's
(`harness/wire.rs`).

## What they cover

| File | |
| --- | --- |
| `login.rs` | placement over the nodes, the server list, nodes refusing players the proxy did not send, a player's data following them to another node, programs without the cluster's token, players no node can take, servers that do not check who players are |
| `clock.rs` | lockstep ticking, `/tick rate` setting the cluster's pace |
| `blocks.rs` | block changes both ways, snapshots, consequences run once on the owner, only the owner ticking, updates across chunks of different nodes, a client building on ground another node simulates |
| `regions.rs` | far-apart areas with different owners, entity mirrors, damage and new entities going to the owner, zones merging, a leaving node handing its area over |
| `players.rs` | players on different nodes seeing and hurting each other, with commands, a mob, and a client's punch |
| `custody.rs` | chests, hoppers, items, sheep and broken chests used across nodes, without duplicates |
| `world_state.rs` | time, weather, game rules, difficulty and world borders |
| `shared_data.rs` | scoreboard, teams, boss bars, storage, stopwatches, who may join, `/tick`, map ids |
| `chat.rs` | chat, `/say`, team messages, arrivals, deaths and departures across nodes |
| `events.rs` | boss bar players and scores across nodes, one dragon fight between two nodes |
| `handoff.rs` | a walking, chatting player moved and back without their client or an onlooker noticing; lodestar moving players on its own |
| `framing.rs` | nodes that do not compress, or compress everything |
| `recovery.rs` | an empty worker recovering the world and players after lodestar restarts, and after a worker crashes holding another node's chest |
| `checkpoint.rs` | a connected player's progress committed at checkpoints |

Not covered: logging in with Mojang authentication (`online_mode = true`),
which needs real accounts.
