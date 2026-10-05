package dev.lodecore;

import dev.lodecore.storage.WorldStorage;
import dev.lodecore.storage.ChunkPersistence;
import dev.lodecore.storage.GlobalWorldData;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import dev.lodecore.handoff.Handoffs;
import dev.lodecore.net.StarLink;
import dev.lodecore.net.Wire;
import dev.lodecore.replication.BlockEntityReplication;
import dev.lodecore.replication.BlockReplication;
import dev.lodecore.replication.Custody;
import dev.lodecore.replication.DemandTickets;
import dev.lodecore.replication.DragonFightSync;
import dev.lodecore.replication.EntityIds;
import dev.lodecore.replication.EntityReplication;
import dev.lodecore.replication.Interactions;
import dev.lodecore.replication.RaidSync;
import dev.lodecore.replication.WorldState;
import dev.lodecore.shared.GlobalChat;
import dev.lodecore.shared.MapSync;
import dev.lodecore.shared.SharedData;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.jspecify.annotations.Nullable;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/** This server's membership and tick lifecycle in the cluster. */
public final class Node implements StarLink.Listener {
	static final int REPORT_INTERVAL_TICKS = 20;
	static final int NOT_RESOLVED = -1;
	/** lodestar's id for "no node". */
	public static final int NO_NODE = 0;

	final MinecraftServer server;
	final GlobalWorldData globalData = new GlobalWorldData(this);
	final ChunkPersistence persistence = new ChunkPersistence(this);
	final NodeChunks chunks = new NodeChunks(this);
	final NodeMessages messages = new NodeMessages(this);
	final NodeCheckpoint checkpoint = new NodeCheckpoint(this);
	private final NodeTicks ticks = new NodeTicks(this);
	final LodecoreConfig config;
	final BlockReplication blocks = new BlockReplication(this);
	final EntityReplication entities = new EntityReplication(this);
	final BlockEntityReplication blockEntities = new BlockEntityReplication(this);
	final Custody custody = new Custody(this);
	final Interactions interactions = new Interactions(this);
	final PlayerDataSync playerData = new PlayerDataSync(this);
	final DemandTickets demands = new DemandTickets();
	final EntityIds entityIds = new EntityIds();
	final Handoffs handoffs = new Handoffs(this);
	final WorldState worldState = new WorldState(this);
	final SharedData shared = new SharedData(this);
	final MapSync maps = new MapSync(this);
	final RaidSync raids = new RaidSync(this);
	final DragonFightSync dragonFights = new DragonFightSync(this);
	final GlobalChat chat = new GlobalChat(this);
	/** Null until the server is ready for players. */
	StarLink link;

	/**
	 * What the link reported, in order: {@link Connected}, {@link #DISCONNECTED}, or a message.
	 * It is handled at the start of a tick, up to and including the {@link Wire.Tick} that starts
	 * it. Checkpoint drain rounds also apply replication while simulation is stopped.
	 */
	final BlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
	static final Object DISCONNECTED = new Object();

	private record Connected(int nodeId) {
	}

	/** Where a player is, as reported to lodestar. */
	record Location(int dimension, long chunk) {
	}

	// Written by the link thread.
	volatile boolean linkUp;
	final AtomicLong ticksGranted = new AtomicLong();

	long ticksStarted;
	/** The cluster tick being run, or -1 if this tick was not started by lodestar. */
	long clusterTick = -1;

	boolean connected;
	int nodeId = NO_NODE;
	/** lodestar's id for each dimension, valid for the current connection only. */
	final Map<ResourceKey<Level>, Integer> dimensionIds = new HashMap<>();
	final Map<Integer, ServerLevel> levelsById = new HashMap<>();
	/** Which node simulates each region in use, as far as lodestar has said, by packed region position. */
	final Map<ResourceKey<Level>, Long2IntMap> regionOwners = new HashMap<>();
	/** The node each player in the cluster is homed on. */
	final Map<UUID, Integer> homes = new HashMap<>();
	/** Chunks this server has loaded, packed as {@link ChunkPos#pack()}. */
	final Map<ResourceKey<Level>, LongSet> loadedChunks = new HashMap<>();
	/** Chunks lodestar has been told this node would tick. */
	final Map<ResourceKey<Level>, LongSet> reportedTicking = new HashMap<>();
	/** Where lodestar has been told each player homed here is. */
	final Map<UUID, Location> reportedPlayers = new HashMap<>();

	/** Created before the world loads, so that no chunk event is missed. */
	Node(MinecraftServer server, LodecoreConfig config) {
		this.server = server;
		this.config = config;
	}

	private static String defaultAddress(MinecraftServer server) {
		String host = server.getLocalIp();

		if (host == null || host.isEmpty()) {
			try {
				host = InetAddress.getLocalHost().getHostAddress();
			} catch (UnknownHostException e) {
				host = InetAddress.getLoopbackAddress().getHostAddress();
			}
		}

		return host + ":" + server.getPort();
	}

	/** Joins the cluster. Called once the server is ready for players. */
	void start() {
		String advertised = config.advertisedAddress().isEmpty() ? defaultAddress(server) : config.advertisedAddress();
		byte[] hello = Wire.hello(config.token(), "txn2:" + WorldStorage.epoch() + ":" + config.nodeName(), advertised, server.getMaxPlayers());
		InetSocketAddress lodestar = new InetSocketAddress(config.lodestar().getHost(), config.lodestar().getPort());
		Lodecore.LOGGER.info("This node advertises {} to the cluster", advertised);
		worldState.watchBorders();
		shared.start();
		link = new StarLink(lodestar, hello, this);
		link.start();
	}

	void stop() {
		if (link != null) {
			link.close();
		}
	}

	public MinecraftServer server() { return server; }

	public boolean isConnected() { return connected && linkUp; }

	/** lodestar's id for this node, or {@link #NO_NODE} while not connected. */
	public int nodeId() { return nodeId; }

	/** The region a chunk is in, packed like a {@link ChunkPos}. */
	public static long regionOf(long chunk) {
		return ChunkPos.pack(ChunkPos.getX(chunk) >> Wire.REGION_SHIFT, ChunkPos.getZ(chunk) >> Wire.REGION_SHIFT);
	}

	/**
	 * Whether this node is the one that simulates a chunk: the owner of the region it is in.
	 *
	 * <p>Before joining, world loading uses local simulation; losing lodestar stops the server.
	 * While connected, a region whose owner lodestar has not named yet is not simulated.
	 */
	public boolean owns(ServerLevel level, long chunk) {
		return owns(level.dimension(), chunk);
	}

	public boolean owns(ResourceKey<Level> dimension, long chunk) {
		return !connected || ownerOf(dimension, chunk) == nodeId;
	}

	/** The node that simulates a chunk, or {@link #NO_NODE} if lodestar has not said. */
	public int ownerOf(ResourceKey<Level> dimension, long chunk) {
		Long2IntMap owners = regionOwners.get(dimension);
		return owners == null ? NO_NODE : owners.getOrDefault(regionOf(chunk), NO_NODE);
	}

	/** The node a player is homed on, or {@link #NO_NODE} if lodestar has not said. */
	public int homeOf(UUID player) {
		return homes.getOrDefault(player, NO_NODE);
	}

	/** lodestar's id for a dimension, or -1 while it is not known. */
	public int dimensionId(ResourceKey<Level> dimension) {
		return dimensionIds.getOrDefault(dimension, NOT_RESOLVED);
	}

	public @Nullable ServerLevel level(int dimensionId) {
		return levelsById.get(dimensionId);
	}

	/** Whether this server has a chunk loaded, as far as the chunk events have said. */
	public boolean hasLoaded(ServerLevel level, long chunk) {
		return loadedChunks.getOrDefault(level.dimension(), LongSet.of()).contains(chunk);
	}

	/** Whether another node has a chunk loaded. Only known for chunks this node owns. */
	public boolean isNeededElsewhere(ServerLevel level, long chunk) {
		return demands.isDemanded(level.dimension(), chunk);
	}

	/** Queues a frame for lodestar. Dropped if the link is down. */
	public void send(byte[] frame) {
		if (link != null) {
			link.send(frame);
		}
	}

	// ---- called on the link thread ----

	@Override
	public void onConnected(int nodeId) {
		WorldStorage.connected(nodeId);
		inbox.add(new Connected(nodeId));
		linkUp = true;
	}

	@Override
	public void onMessage(Wire.Inbound message) {
		if (WorldStorage.receive(message)) return;
		inbox.add(message);

		if (message instanceof Wire.Tick) {
			ticksGranted.incrementAndGet();
		}
	}

	@Override
	public void onDisconnected() {
		WorldStorage.disconnected();
		server.halt(false);
		if (link != null) link.close();
		linkUp = false;
		inbox.add(DISCONNECTED);
		if (!checkpoint.isStopped()) dev.lodecore.storage.SessionFailure.stop(server);
	}

	// ---- the tick ----

	/**
	 * Waits for lodestar to start the next tick, then applies everything the other nodes sent
	 * during the last one. Losing Lodestar halts this server.
	 */
	public void beforeTick() {
		// Queued game tasks keep running while this waits.
		server.managedBlock(() -> !linkUp || ticksGranted.get() > ticksStarted);
		ClientPackets.batch(server, this::applyTickMessages);
	}

	private void applyTickMessages() {
		for (Object event = inbox.poll(); event != null; event = inbox.poll()) {
			switch (event) {
				case Connected(int id) -> onLinkUp(id);
				case Wire.Tick(long tick) -> {
					ticksStarted++;
					clusterTick = tick;
					// Anything after this belongs to the next tick.
					return;
				}
				case Wire.Inbound message -> messages.onMessage0(message);
				default -> onLinkDown();
			}
		}

		clusterTick = -1;
	}

	void afterTick() { ticks.afterTick(); }

	private void onLinkUp(int nodeId) {
		this.connected = true;
		this.nodeId = nodeId;

		// lodestar knows nothing about this node yet, so describe it from scratch.
		for (ServerLevel level : server.getAllLevels()) {
			send(Wire.resolveDimension(level.dimension().identifier().toString()));
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			send(Wire.playerJoin(player.getUUID(), player.getGameProfile().name()));
		}

		playerData.onConnected();
	}

	private void onLinkDown() {
		connected = false;
		nodeId = NO_NODE;
		dimensionIds.clear();
		levelsById.clear();
		regionOwners.clear();
		homes.clear();
		reportedTicking.clear();
		reportedPlayers.clear();
		blocks.onDisconnected();
		entities.onDisconnected();
		blockEntities.onDisconnected();
		custody.onDisconnected();
		playerData.onDisconnected();
		demands.clear(server);
		entityIds.onDisconnected();
		handoffs.onDisconnected();
		worldState.onDisconnected();
		shared.onDisconnected();
		maps.onDisconnected();
		raids.onDisconnected();
		dragonFights.onDisconnected();
	}

	/** A player homed here moved to another node. */
	public void onHandedOff(ServerPlayer player) {
		reportedPlayers.remove(player.getUUID());
	}

	void onPlayerJoin(ServerPlayer player) {
		send(Wire.playerJoin(player.getUUID(), player.getGameProfile().name()));
		entities.introduceRemotePlayers(player);
		playerData.onJoined(player);
	}

	void onPlayerLeave(ServerPlayer player) {
		reportedPlayers.remove(player.getUUID());
		send(Wire.playerLeave(player.getUUID()));
	}

	void onChunkLoad(ServerLevel level, ChunkPos pos) { chunks.onChunkLoad(level, pos); }
	void onChunkAccessible(ServerLevel level, ChunkPos pos) { chunks.onChunkAccessible(level, pos); }
	void onChunkUnload(ServerLevel level, ChunkPos pos) { chunks.onChunkUnload(level, pos); }

	public BlockReplication blocks() { return blocks; }

	public EntityReplication entities() { return entities; }

	public BlockEntityReplication blockEntities() { return blockEntities; }

	public Custody custody() { return custody; }

	public Interactions interactions() { return interactions; }

	public PlayerDataSync playerData() { return playerData; }

	public EntityIds entityIds() { return entityIds; }

	public Handoffs handoffs() { return handoffs; }

	public WorldState worldState() { return worldState; }

	public SharedData shared() { return shared; }

	public MapSync maps() { return maps; }

	public RaidSync raids() { return raids; }

	public DragonFightSync dragonFights() { return dragonFights; }

	public GlobalChat chat() { return chat; }
}
