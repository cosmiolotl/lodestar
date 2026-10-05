package dev.lodecore.replication;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.lodecore.Node;
import dev.lodecore.mixin.LevelTicksAccessor;
import dev.lodecore.mixin.ServerClockInstanceAccessor;
import dev.lodecore.mixin.ServerTickRateManagerAccessor;
import dev.lodecore.net.Wire;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;

/**
 * The state a server keeps besides its chunks and players, kept the same on every node: the time
 * (the game time, and the world clocks that give the time of day), the weather, the game rules,
 * the difficulty, the world spawn, how fast the game runs ({@code /tick}) and the default game
 * mode, which a server keeps once for all of its dimensions, and the world border, which it keeps
 * for each dimension.
 *
 * <p>lodestar names one node the <em>master</em> of each scope: of the cluster, for what a server
 * keeps once, and of each dimension, for what it keeps per dimension. The master's copy is the
 * truth. It alone runs the weather cycle, and at the end of a tick it broadcasts its copy of
 * whatever changed in it, and every five seconds all of it, which the other nodes take on at the
 * start of the next tick. Clocks, the game time and a moving world border advance by the same
 * amount on every node, since the nodes tick in lockstep, so what the master has at the end of a
 * tick is what the others should have at the start of the next; a node that has something else,
 * having just joined or missed something, is put right then.
 *
 * <p>A change made on another node, by a command or by its players sleeping through the night, is
 * made there at once, so that its players see it, and sent to the master, which takes it on and
 * broadcasts it as a change of its own. Until the master can have answered, the node that made the
 * change ignores the master's older copy of what it changed.
 *
 * <p>A node that joins a scope asks its master for the whole of it. Cut off from lodestar, a node
 * keeps its own state, as a standalone server does.
 *
 * <p>The master of the cluster also tells lodestar how long a tick should take, as {@code /tick}
 * has set it, since lodestar's clock sets the pace of every node.
 *
 * <p>Everything here runs on the game thread.
 */
public final class WorldState {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/world");

	// Payloads, broadcast to a scope or sent to one node.
	/** The master's copy of parts of a scope: broadcast, or sent to a node that asked. */
	private static final int STATE = 10;
	/** Asks the master of a scope for the whole of it. */
	private static final int STATE_REQUEST = 11;
	/** Parts of a scope that changed on a node other than its master, sent to the master. */
	private static final int STATE_CHANGE = 12;

	// The parts of a scope. The cluster's:
	public static final int GAME_TIME = 1;
	public static final int CLOCKS = 2;
	public static final int WEATHER = 3;
	public static final int GAME_RULES = 4;
	public static final int DIFFICULTY = 5;
	public static final int SPAWN = 6;
	// A dimension's:
	public static final int BORDER = 7;
	// The cluster's again:
	public static final int TICK = 8;
	public static final int DEFAULT_GAME_MODE = 9;
	private static final int PARTS = 10;

	private static final int CLUSTER_PARTS = bit(GAME_TIME) | bit(CLOCKS) | bit(WEATHER) | bit(GAME_RULES) | bit(DIFFICULTY) | bit(SPAWN)
			| bit(TICK) | bit(DEFAULT_GAME_MODE);
	private static final int DIMENSION_PARTS = bit(BORDER);

	/** How often a master broadcasts the whole of its scopes, whether or not anything changed. */
	private static final int REFRESH_TICKS = 100;
	/**
	 * How far, in blocks, a moving world border can be from the master's before it is put right.
	 * Each node works out where it is from where it started moving, so it can be off in the last
	 * digits.
	 */
	private static final double BORDER_TOLERANCE = 1.0E-4;
	/**
	 * How many ticks of a sprint a node can be off from the master's before it is put right. A node
	 * counts a tick of the sprint off before the tick starts, and so before it takes on what the
	 * master had at the end of the last one.
	 */
	private static final int SPRINT_TOLERANCE = 2;

	private final Node node;
	/** The master of each scope, as lodestar has named them. */
	private final Int2IntMap masters = new Int2IntOpenHashMap();
	/** Set while taking on another node's state, so that it is not taken for a change made here. */
	private boolean applying;
	/** The parts of each scope that changed here since the end of the last tick, as bits. */
	private final Int2IntMap changed = new Int2IntOpenHashMap();
	/** The clocks and game rules among them. */
	private final Set<Holder<WorldClock>> changedClocks = new HashSet<>();
	private final Set<GameRule<?>> changedRules = new HashSet<>();
	/** The tick at whose end this node last sent the master a change to each part, by scope. */
	private final Int2ObjectMap<int[]> changeSent = new Int2ObjectOpenHashMap<>();
	/** Nodes that asked for the whole of a scope, answered at the end of the tick. */
	private final List<Request> requests = new ArrayList<>();
	/** Whether it rained and thundered in the weather this node last broadcast as master. */
	private boolean broadcastRaining;
	private boolean broadcastThundering;
	/** The length of a tick this node last asked lodestar for as master, or -1 if it has not. */
	private long sentTickInterval = -1;
	/**
	 * Whether this node alone is frozen, by {@code /lodecore freeze}, for tests that need one node to
	 * stand still. Its being frozen is neither sent to the master nor undone by it.
	 */
	private boolean frozenHere;

	private record Request(int from, int scope) {
	}

	public WorldState(Node node) {
		this.node = node;
		masters.defaultReturnValue(Node.NO_NODE);
	}

	private static int bit(int part) {
		return 1 << part;
	}

	private static int partsOf(int scope) {
		return scope == Wire.CLUSTER_SCOPE ? CLUSTER_PARTS : DIMENSION_PARTS;
	}

	/** Whether a payload sent to this node alone is for this class. */
	public static boolean handlesDirect(int kind) {
		return kind == STATE || kind == STATE_REQUEST || kind == STATE_CHANGE;
	}

	/** Starts listening for changes to each dimension's world border. Called once the levels are loaded. */
	public void watchBorders() {
		for (ServerLevel level : node.server().getAllLevels()) {
			level.getWorldBorder().addListener(new BorderChangeListener() {
				@Override
				public void onSetSize(WorldBorder border, double newSize) {
					onBorderChanged(level);
				}

				@Override
				public void onLerpSize(WorldBorder border, double fromSize, double targetSize, long ticks, long gameTime) {
					onBorderChanged(level);
				}

				@Override
				public void onSetCenter(WorldBorder border, double x, double z) {
					onBorderChanged(level);
				}

				@Override
				public void onSetWarningTime(WorldBorder border, int time) {
					onBorderChanged(level);
				}

				@Override
				public void onSetWarningBlocks(WorldBorder border, int blocks) {
					onBorderChanged(level);
				}

				@Override
				public void onSetDamagePerBlock(WorldBorder border, double damagePerBlock) {
					onBorderChanged(level);
				}

				@Override
				public void onSetSafeZone(WorldBorder border, double safeZone) {
					onBorderChanged(level);
				}
			});
		}
	}

	// ---- who keeps what ----

	/** Whether this node keeps the state of a scope. Cut off from lodestar, it keeps all of its own. */
	public boolean isMaster(int scope) {
		return !node.isConnected() || masters.get(scope) == node.nodeId();
	}

	public void onMaster(int scope, int master) {
		int previous = masters.put(scope, master);

		if (previous == master) {
			return;
		}

		LOGGER.info("The state of {} is kept by {}", describe(scope), master == node.nodeId() ? "this node" : "node #" + master);

		if (master == node.nodeId()) {
			// This node's copy is the truth from now on; make sure every other node has it.
			changed.mergeInt(scope, partsOf(scope), (a, b) -> a | b);
			sentTickInterval = -1;
		} else if (master != Node.NO_NODE) {
			node.send(Wire.direct(master, request(scope)));
		}
	}

	private String describe(int scope) {
		if (scope == Wire.CLUSTER_SCOPE) {
			return "the cluster";
		}

		ServerLevel level = node.level(scope);
		return level == null ? "dimension #" + scope : level.dimension().identifier().toString();
	}

	// ---- changes made here ----

	/** A part of a scope changed here. */
	public void onChanged(int scope, int part) {
		if (node.isConnected() && !applying) {
			changed.mergeInt(scope, bit(part), (a, b) -> a | b);
		}
	}

	public void onClockChanged(Holder<WorldClock> clock) {
		if (node.isConnected() && !applying) {
			changedClocks.add(clock);
			onChanged(Wire.CLUSTER_SCOPE, CLOCKS);
		}
	}

	public void onGameRuleChanged(GameRule<?> rule) {
		if (node.isConnected() && !applying) {
			changedRules.add(rule);
			onChanged(Wire.CLUSTER_SCOPE, GAME_RULES);
		}
	}

	private void onBorderChanged(ServerLevel level) {
		int dimension = node.dimensionId(level.dimension());

		// -1 while lodestar has not given the dimension an id.
		if (dimension >= 0) {
			onChanged(dimension, BORDER);
		}
	}

	/**
	 * At the end of a tick: a master broadcasts what changed in its scopes and answers the nodes
	 * that asked for them, and any other node sends the master what changed here.
	 */
	public void flush() {
		int tick = node.server().getTickCount();
		boolean refresh = tick % REFRESH_TICKS == 0;
		flush(Wire.CLUSTER_SCOPE, tick, refresh);

		for (ServerLevel level : node.server().getAllLevels()) {
			int dimension = node.dimensionId(level.dimension());

			if (dimension >= 0) {
				flush(dimension, tick, refresh);
			}
		}

		requests.clear();
	}

	private void flush(int scope, int tick, boolean refresh) {
		int master = masters.get(scope);

		// What changed waits until there is a master to send it to.
		if (master == Node.NO_NODE) {
			return;
		}

		int parts = changed.remove(scope);

		if (master == node.nodeId()) {
			if (scope == Wire.CLUSTER_SCOPE && weatherTurned()) {
				parts |= bit(WEATHER);
			}

			if (refresh) {
				parts = partsOf(scope);
			}

			if (parts != 0) {
				node.send(Wire.broadcast(scope, write(STATE, scope, parts, false)));

				if ((parts & bit(WEATHER)) != 0) {
					WeatherData weather = node.server().getWeatherData();
					broadcastRaining = weather.isRaining();
					broadcastThundering = weather.isThundering();
				}
			}

			for (Request request : requests) {
				if (request.scope() == scope) {
					node.send(Wire.direct(request.from(), write(STATE, scope, partsOf(scope), false)));
				}
			}

			if (scope == Wire.CLUSTER_SCOPE) {
				sendTickInterval();
			}
		} else if (parts != 0) {
			node.send(Wire.direct(master, write(STATE_CHANGE, scope, parts, true)));
			int[] sent = changeSent.computeIfAbsent(scope, key -> new int[PARTS]);

			for (int part = 1; part < PARTS; part++) {
				if ((parts & bit(part)) != 0) {
					sent[part] = tick;
				}
			}
		}

		if (scope == Wire.CLUSTER_SCOPE) {
			changedClocks.clear();
			changedRules.clear();
		}
	}

	/** As master, tells lodestar how long a tick takes here, if that changed: none at all while sprinting. */
	private void sendTickInterval() {
		ServerTickRateManager ticks = node.server().tickRateManager();
		long interval = ticks.isSprinting() ? 0 : ticks.nanosecondsPerTick();

		if (interval != sentTickInterval) {
			node.send(Wire.tickInterval(interval));
			sentTickInterval = interval;
		}
	}

	/**
	 * Freezes or unfreezes this node alone, whatever the rest of the cluster does. Unfrozen, it
	 * takes on the cluster's {@code /tick} state again.
	 */
	public void freezeHere(boolean frozen) {
		ServerTickRateManager ticks = node.server().tickRateManager();
		frozenHere = frozen;
		applying = true;

		try {
			ticks.setFrozen(frozen);
		} finally {
			applying = false;
		}

		int master = masters.get(Wire.CLUSTER_SCOPE);

		if (!frozen && node.isConnected() && master != Node.NO_NODE && master != node.nodeId()) {
			node.send(Wire.direct(master, request(Wire.CLUSTER_SCOPE)));
		}
	}

	/** Whether rain or thunder started or stopped since this node last broadcast the weather. */
	private boolean weatherTurned() {
		WeatherData weather = node.server().getWeatherData();
		return weather.isRaining() != broadcastRaining || weather.isThundering() != broadcastThundering;
	}

	public void onDisconnected() {
		masters.clear();
		changed.clear();
		changedClocks.clear();
		changedRules.clear();
		changeSent.clear();
		requests.clear();
		sentTickInterval = -1;
	}

	// ---- payloads ----

	private byte[] request(int scope) {
		RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(5), node.server().registryAccess());
		out.writeByte(STATE_REQUEST);
		out.writeInt(scope);
		return ByteBufUtil.getBytes(out);
	}

	/**
	 * Writes parts of a scope. {@code onlyChanged} leaves out the clocks and game rules that did
	 * not change here, so that a change made here does not undo one made elsewhere meanwhile.
	 */
	private byte[] write(int kind, int scope, int parts, boolean onlyChanged) {
		MinecraftServer server = node.server();
		RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
		out.writeByte(kind);
		out.writeInt(scope);

		for (int part = 1; part < PARTS; part++) {
			if ((parts & bit(part)) == 0) {
				continue;
			}

			ServerLevel level = node.level(scope);

			if (part == BORDER && level == null) {
				continue;
			}

			out.writeByte(part);

			switch (part) {
				case GAME_TIME -> out.writeLong(server.overworld().getGameTime());
				case CLOCKS -> writeClocks(out, onlyChanged ? changedClocks : allClocks());
				case WEATHER -> {
					WeatherData weather = server.getWeatherData();
					out.writeInt(weather.getClearWeatherTime());
					out.writeInt(weather.getRainTime());
					out.writeInt(weather.getThunderTime());
					out.writeBoolean(weather.isRaining());
					out.writeBoolean(weather.isThundering());
				}
				case GAME_RULES -> writeGameRules(out, onlyChanged ? changedRules : server.getGameRules().availableRules().toList());
				case DIFFICULTY -> {
					Difficulty.STREAM_CODEC.encode(out, server.getWorldData().getDifficulty());
					out.writeBoolean(server.getWorldData().isDifficultyLocked());
				}
				case SPAWN -> LevelData.RespawnData.STREAM_CODEC.encode(out, server.getWorldData().overworldData().getRespawnData());
				case TICK -> {
					ServerTickRateManager ticks = server.tickRateManager();
					out.writeFloat(ticks.tickrate());
					out.writeBoolean(ticks.isFrozen() && !frozenHere);
					out.writeVarInt(ticks.frozenTicksToRun());
					out.writeVarLong(((ServerTickRateManagerAccessor) ticks).lodecore$remainingSprintTicks());
				}
				case DEFAULT_GAME_MODE -> GameType.STREAM_CODEC.encode(out, server.getWorldData().getGameType());
				case BORDER -> {
					WorldBorder.Settings border = new WorldBorder.Settings(level.getWorldBorder());
					out.writeDouble(border.centerX());
					out.writeDouble(border.centerZ());
					out.writeDouble(border.damagePerBlock());
					out.writeDouble(border.safeZone());
					out.writeInt(border.warningBlocks());
					out.writeInt(border.warningTime());
					out.writeDouble(border.size());
					out.writeLong(border.lerpTime());
					out.writeDouble(border.lerpTarget());
				}
				default -> throw new IllegalStateException("no such part " + part);
			}
		}

		return ByteBufUtil.getBytes(out);
	}

	private List<Holder<WorldClock>> allClocks() {
		return node.server().registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).listElements().<Holder<WorldClock>>map(clock -> clock).toList();
	}

	private void writeClocks(RegistryFriendlyByteBuf out, Collection<Holder<WorldClock>> clocks) {
		ServerClockManager manager = node.server().clockManager();
		out.writeVarInt(clocks.size());

		for (Holder<WorldClock> clock : clocks) {
			ServerClockManager.ServerClockInstance instance = manager.getInstance(clock);
			out.writeIdentifier(clock.unwrapKey().orElseThrow().identifier());
			out.writeLong(instance.totalTicks());
			out.writeFloat(instance.partialTick());
			out.writeFloat(instance.rate());
			out.writeBoolean(instance.isPaused());
		}
	}

	private void writeGameRules(RegistryFriendlyByteBuf out, Collection<GameRule<?>> rules) {
		GameRules gameRules = node.server().getGameRules();
		out.writeVarInt(rules.size());

		for (GameRule<?> rule : rules) {
			out.writeIdentifier(rule.getIdentifier());
			out.writeUtf(gameRules.getAsString(rule));
		}
	}

	/**
	 * Another node sent this node something, to it alone or to the whole of a scope: the master's
	 * state, a request for it, or a change made on another node.
	 */
	public void onPayload(int from, ByteBuffer payload) {
		try {
			RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload), node.server().registryAccess());
			int kind = in.readByte();
			int scope = in.readInt();

			switch (kind) {
				// lodestar relays a broadcast only from the master, but an answer to a request
				// can come from one that has just given up the scope.
				case STATE -> {
					if (from == masters.get(scope)) {
						read(scope, in, false);
					}
				}
				case STATE_CHANGE -> {
					if (isMaster(scope)) {
						// Broadcast at the end of the tick, like a change made here.
						changed.mergeInt(scope, read(scope, in, true), (a, b) -> a | b);
					}
				}
				case STATE_REQUEST -> {
					if (isMaster(scope)) {
						requests.add(new Request(from, scope));
					}
				}
				default -> LOGGER.warn("Node #{} sent an unknown payload {}", from, kind);
			}
		} catch (IndexOutOfBoundsException | DecoderException | IllegalArgumentException e) {
			LOGGER.warn("Node #{} sent a malformed payload: {}", from, e.toString());
		}
	}

	/**
	 * Takes on the parts of a scope another node sent: the master's state, or, on the master, a
	 * change made on another node. Returns the parts that were taken on, as bits.
	 */
	private int read(int scope, RegistryFriendlyByteBuf in, boolean change) {
		int tick = node.server().getTickCount();
		int[] sent = changeSent.get(scope);
		int unsent = changed.get(scope);
		int taken = 0;
		applying = true;

		try {
			while (in.isReadable()) {
				int part = in.readByte();

				if (part <= 0 || part >= PARTS || (partsOf(scope) & bit(part)) == 0) {
					throw new IllegalArgumentException("unknown part " + part + " of scope " + scope);
				}

				// A change made here is newer than the master's copy until the master can have had
				// it: while it waits to be sent (commands run between ticks, before what the master
				// sent during the last one is taken on), and for a tick after it was sent.
				boolean stale = !change && ((unsent & bit(part)) != 0 || sent != null && sent[part] >= tick);
				// The game time only advances, which every node does on its own.
				boolean apply = !stale && !(change && part == GAME_TIME);

				switch (part) {
					case GAME_TIME -> {
						long time = in.readLong();

						if (apply) {
							takeGameTime(time);
						}
					}
					case CLOCKS -> takeClocks(in, apply);
					case WEATHER -> takeWeather(in, apply);
					case GAME_RULES -> takeGameRules(in, apply);
					case DIFFICULTY -> takeDifficulty(in, apply);
					case SPAWN -> {
						LevelData.RespawnData spawn = LevelData.RespawnData.STREAM_CODEC.decode(in);

						if (apply) {
							node.server().setRespawnData(spawn);
						}
					}
					case BORDER -> takeBorder(node.level(scope), in, apply);
					case TICK -> takeTick(in, apply);
					case DEFAULT_GAME_MODE -> {
						GameType mode = GameType.STREAM_CODEC.decode(in);

						if (apply && node.server().getWorldData().getGameType() != mode) {
							// As /defaultgamemode does.
							node.server().setDefaultGameType(mode);
							node.server().enforceGameTypeForPlayers(node.server().getForcedGameType());
						}
					}
					default -> throw new IllegalStateException("no such part " + part);
				}

				if (apply) {
					taken |= bit(part);
				}
			}
		} finally {
			applying = false;
		}

		return taken;
	}

	private void takeGameTime(long time) {
		MinecraftServer server = node.server();
		long local = server.overworld().getGameTime();

		if (time == local) {
			return;
		}

		LOGGER.info("Taking on the game time of the cluster: {} rather than {}", time, local);
		server.getWorldData().overworldData().setGameTime(time);

		// Block and fluid ticks are scheduled for a game time; keep how far off they are.
		for (ServerLevel level : server.getAllLevels()) {
			shift(level.getBlockTicks(), time - local);
			shift(level.getFluidTicks(), time - local);
		}

		server.forceGameTimeSynchronization();
	}

	@SuppressWarnings("unchecked")
	private static <T> void shift(LevelTicks<T> ticks, long delta) {
		for (LevelChunkTicks<T> container : ((LevelTicksAccessor<T>) (Object) ticks).lodecore$allContainers().values()) {
			List<ScheduledTick<T>> scheduled = container.getAll().toList();

			if (scheduled.isEmpty()) {
				continue;
			}

			container.removeIf(tick -> true);

			for (ScheduledTick<T> tick : scheduled) {
				container.schedule(new ScheduledTick<>(tick.type(), tick.pos(), tick.triggerTick() + delta, tick.priority(), tick.subTickOrder()));
			}
		}
	}

	private void takeClocks(RegistryFriendlyByteBuf in, boolean apply) {
		MinecraftServer server = node.server();
		Registry<WorldClock> registry = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK);
		ServerClockManager manager = server.clockManager();
		Map<Holder<WorldClock>, ClockNetworkState> updates = new HashMap<>();
		int count = in.readVarInt();

		for (int i = 0; i < count; i++) {
			Identifier id = in.readIdentifier();
			long totalTicks = in.readLong();
			float partialTick = in.readFloat();
			float rate = in.readFloat();
			boolean paused = in.readBoolean();
			Optional<Holder.Reference<WorldClock>> clock = registry.get(id);

			if (!apply || clock.isEmpty()) {
				continue;
			}

			ServerClockManager.ServerClockInstance instance = manager.getInstance(clock.get());

			if (instance.totalTicks() == totalTicks && instance.partialTick() == partialTick && instance.rate() == rate && instance.isPaused() == paused) {
				continue;
			}

			ServerClockInstanceAccessor accessor = (ServerClockInstanceAccessor) (Object) instance;
			accessor.lodecore$setTotalTicks(totalTicks);
			accessor.lodecore$setPartialTick(partialTick);
			accessor.lodecore$setRate(rate);
			accessor.lodecore$setPaused(paused);
			updates.put(clock.get(), instance.packNetworkState(server));
		}

		if (!updates.isEmpty()) {
			// As the game does when a clock is set.
			manager.setDirty();
			server.getPlayerList().broadcastAll(new ClientboundSetTimePacket(server.overworld().getGameTime(), updates));

			for (ServerLevel level : server.getAllLevels()) {
				level.environmentAttributes().invalidateTickCache();
			}
		}
	}

	/** The levels see the new weather on their next tick, and tell their players. */
	private void takeWeather(RegistryFriendlyByteBuf in, boolean apply) {
		int clearTime = in.readInt();
		int rainTime = in.readInt();
		int thunderTime = in.readInt();
		boolean raining = in.readBoolean();
		boolean thundering = in.readBoolean();
		WeatherData weather = node.server().getWeatherData();

		if (!apply) {
			return;
		}

		if (weather.getClearWeatherTime() != clearTime) {
			weather.setClearWeatherTime(clearTime);
		}

		if (weather.getRainTime() != rainTime) {
			weather.setRainTime(rainTime);
		}

		if (weather.getThunderTime() != thunderTime) {
			weather.setThunderTime(thunderTime);
		}

		if (weather.isRaining() != raining) {
			weather.setRaining(raining);
		}

		if (weather.isThundering() != thundering) {
			weather.setThundering(thundering);
		}
	}

	private void takeGameRules(RegistryFriendlyByteBuf in, boolean apply) {
		GameRules gameRules = node.server().getGameRules();
		int count = in.readVarInt();

		for (int i = 0; i < count; i++) {
			Identifier id = in.readIdentifier();
			String value = in.readUtf();
			GameRule<?> rule = BuiltInRegistries.GAME_RULE.getValue(id);

			// A rule of a feature this node does not have is left alone.
			if (apply && rule != null && gameRules.availableRules().anyMatch(available -> available == rule)) {
				takeGameRule(gameRules, rule, value);
			}
		}
	}

	private <T> void takeGameRule(GameRules gameRules, GameRule<T> rule, String value) {
		rule.deserialize(value).result()
				.filter(parsed -> !parsed.equals(gameRules.get(rule)))
				.ifPresent(parsed -> gameRules.set(rule, parsed, node.server()));
	}

	private void takeDifficulty(RegistryFriendlyByteBuf in, boolean apply) {
		Difficulty difficulty = Difficulty.STREAM_CODEC.decode(in);
		boolean locked = in.readBoolean();
		MinecraftServer server = node.server();
		WorldData world = server.getWorldData();

		if (!apply) {
			return;
		}

		if (world.getDifficulty() != difficulty) {
			server.setDifficulty(difficulty, true);
		}

		if (world.isDifficultyLocked() != locked) {
			server.setDifficultyLocked(locked);
		}
	}

	/** Goes through the setters {@code /tick} uses, which tell this node's players. */
	private void takeTick(RegistryFriendlyByteBuf in, boolean apply) {
		float rate = in.readFloat();
		boolean frozen = in.readBoolean();
		int steps = in.readVarInt();
		long sprint = in.readVarLong();

		if (!apply) {
			return;
		}

		ServerTickRateManager ticks = node.server().tickRateManager();

		if (ticks.tickrate() != rate) {
			ticks.setTickRate(rate);
		}

		long sprintHere = ((ServerTickRateManagerAccessor) ticks).lodecore$remainingSprintTicks();

		if (sprint > 0 && (!ticks.isSprinting() || Math.abs(sprintHere - sprint) > SPRINT_TOLERANCE)) {
			ticks.requestGameToSprint((int) Math.min(sprint, Integer.MAX_VALUE));
		} else if (sprint == 0 && ticks.isSprinting()) {
			ticks.stopSprinting();
		}

		if (!frozenHere && ticks.isFrozen() != frozen) {
			ticks.setFrozen(frozen);
		}

		if (ticks.frozenTicksToRun() != steps) {
			if (steps > 0) {
				ticks.stepGameIfPaused(steps);
			} else {
				ticks.stopStepping();
			}
		}
	}

	/** Goes through the border's setters, which tell the dimension's players. */
	private void takeBorder(ServerLevel level, RegistryFriendlyByteBuf in, boolean apply) {
		double centerX = in.readDouble();
		double centerZ = in.readDouble();
		double damagePerBlock = in.readDouble();
		double safeZone = in.readDouble();
		int warningBlocks = in.readInt();
		int warningTime = in.readInt();
		double size = in.readDouble();
		long lerpTime = in.readLong();
		double lerpTarget = in.readDouble();

		if (!apply || level == null) {
			return;
		}

		WorldBorder border = level.getWorldBorder();

		if (border.getCenterX() != centerX || border.getCenterZ() != centerZ) {
			border.setCenter(centerX, centerZ);
		}

		if (border.getDamagePerBlock() != damagePerBlock) {
			border.setDamagePerBlock(damagePerBlock);
		}

		if (border.getSafeZone() != safeZone) {
			border.setSafeZone(safeZone);
		}

		if (border.getWarningBlocks() != warningBlocks) {
			border.setWarningBlocks(warningBlocks);
		}

		if (border.getWarningTime() != warningTime) {
			border.setWarningTime(warningTime);
		}

		if (lerpTime > 0) {
			if (border.getLerpTime() != lerpTime || border.getLerpTarget() != lerpTarget || Math.abs(border.getSize() - size) > BORDER_TOLERANCE) {
				border.lerpSizeBetween(size, lerpTarget, lerpTime, level.getGameTime());
			}
		} else if (border.getLerpTime() > 0 || border.getSize() != size) {
			border.setSize(size);
		}
	}
}
