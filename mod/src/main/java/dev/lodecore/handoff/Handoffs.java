package dev.lodecore.handoff;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import dev.lodecore.Node;
import dev.lodecore.mixin.ChunkMapAccessor;
import dev.lodecore.mixin.PlayerChunkSenderAccessor;
import dev.lodecore.mixin.PlayerListAccessor;
import dev.lodecore.mixin.ServerCommonPacketListenerImplAccessor;
import dev.lodecore.mixin.ServerWaypointManagerAccessor;
import dev.lodecore.mixin.TrackedEntityAccessor;
import dev.lodecore.net.Wire;
import dev.lodecore.replication.EntityIds;
import dev.lodecore.replication.MirrorConnection;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.bossevents.CustomBossEvent;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

/**
 * Moves players homed on this node to other nodes, and takes over players moving here, without
 * their clients noticing. lodestar runs each move; see {@code crates/lodestar/src/state/handoff.rs}
 * for its steps.
 *
 * <p>On the node a player leaves, the player is let go of at the end of a tick, once every packet
 * the proxy passed on before it stopped has been handled: what their client was last sent is
 * written down, their connection hangs up once the last of that has gone out, and the player
 * stays in the world as the mirror of a remote player, without anybody being told they left.
 *
 * <p>On the node a player arrives at, the world around them is loaded and caught up with first,
 * and the proxy's second connection for them waits, logged in. When they arrive, the mirror of
 * them this node had becomes a player of its own, on that connection, under the same entity id,
 * and the client is told whatever this node would show it differently.
 *
 * <p>Everything here runs on the game thread.
 */
public final class Handoffs {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/handoff");

	/** Holds the world around a player on their way here, as their own tickets will once they are. */
	private static final TicketType PREPARING = Registry.register(
			BuiltInRegistries.TICKET_TYPE,
			Identifier.fromNamespaceAndPath("lodecore", "handoff"),
			new TicketType(0L, TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));
	/** How long the world stays held around a player after they arrive. */
	private static final int LINGER_TICKS = 100;
	/**
	 * How many rings beyond the view distance the world is held around a player on their way here.
	 * A client's view takes in the ring around its view distance, and the game sends a chunk only
	 * once it ticks, which takes one more ring loaded around it, as a player's own tickets give.
	 */
	private static final int HELD_BEYOND_VIEW = 2;

	private final Node node;
	/** Players to let go of, once their packets are in. */
	private final Map<UUID, Outgoing> outgoing = new HashMap<>();
	/** Players on their way here. */
	private final Map<UUID, Incoming> incoming = new HashMap<>();
	/** Tickets held for players who have arrived, and when to let go of them. */
	private final List<Held> lingering = new ArrayList<>();

	private record Outgoing(UUID uuid, int to, int dimension, long frames) {
	}

	private static final class Incoming {
		final UUID uuid;
		final long token;
		final ServerLevel level;
		/** Where the world is held, which follows the player's mirror. */
		ChunkPos center;
		final int radius;
		@Nullable Connection connection;
		@Nullable GameProfile profile;
		boolean ready;

		Incoming(UUID uuid, long token, ServerLevel level, ChunkPos center, int radius) {
			this.uuid = uuid;
			this.token = token;
			this.level = level;
			this.center = center;
			this.radius = radius;
		}
	}

	private record Held(ServerLevel level, ChunkPos center, int radius, int until) {
	}

	public Handoffs(Node node) {
		this.node = node;
	}

	/** Registers the ticket type. Must be called while the mod initialises. */
	public static void init() {
		// Loading the class does the work.
	}

	// ---- the node a player leaves ----

	public void onCut(Wire.HandoffCut cut) {
		if (node.server().getPlayerList().getPlayer(cut.uuid()) == null) {
			LOGGER.info("Asked to let go of {}, who is not here", cut.uuid());
			node.send(Wire.handoffAbort(cut.uuid()));
			return;
		}

		outgoing.put(cut.uuid(), new Outgoing(cut.uuid(), cut.to(), cut.dimension(), cut.frames()));
	}

	/**
	 * Lets go of the players lodestar asked for, whose packets are all in. Runs at the end of the
	 * tick, after its changes have gone out.
	 */
	private void cutReady() {
		MinecraftServer server = node.server();

		for (Iterator<Outgoing> it = outgoing.values().iterator(); it.hasNext(); ) {
			Outgoing out = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(out.uuid());

			if (player == null) {
				it.remove();
				LOGGER.info("Asked to let go of {}, who has left", out.uuid());
				node.send(Wire.handoffAbort(out.uuid()));
				continue;
			}

			HandoffConnection connection = (HandoffConnection) ((ServerCommonPacketListenerImplAccessor) player.connection).lodecore$connection();

			if (connection.lodecore$received() < out.frames()) {
				// The proxy holds back what the client sends meanwhile, so the game hears nothing
				// of a player who may be in mid-air; that is not them floating.
				player.connection.resetFlyingTicks();
				continue;
			}

			it.remove();
			// What came in before the proxy stopped is handled here, and nothing comes after it.
			server.packetProcessor().processQueuedPackets();
			String keep = whyNotNow(player, out);

			if (keep != null) {
				LOGGER.info("Keeping {} here for now: {}", player.getPlainTextName(), keep);
				node.send(Wire.handoffAbort(out.uuid()));
				continue;
			}

			try {
				cut(player);
			} catch (IOException | RuntimeException e) {
				LOGGER.error("Could not let go of {}", player.getPlainTextName(), e);
				node.send(Wire.handoffAbort(out.uuid()));
			}
		}
	}

	/** Why a player cannot be moved right now, or null if they can. */
	private @Nullable String whyNotNow(ServerPlayer player, Outgoing out) {
		if (player.hasDisconnected() || player.isRemoved()) {
			return "they left";
		}

		if (!player.isAlive()) {
			return "they are dead";
		}

		if (node.dimensionId(player.level().dimension()) != out.dimension() || player.isChangingDimension()) {
			return "they changed dimension";
		}

		if (player.containerMenu != player.inventoryMenu) {
			return "they have a menu open";
		}

		if (player.isPassenger() || player.isVehicle()) {
			return "they are riding, or being ridden";
		}

		if (player.isSleeping()) {
			return "they are asleep";
		}

		if (player.getCamera() != player) {
			return "they are spectating through another entity";
		}

		if (node.custody().isWaitingFor(player)) {
			return "they are waiting for something another node has";
		}

		return null;
	}

	private void cut(ServerPlayer player) throws IOException {
		MinecraftServer server = node.server();
		ServerLevel level = player.level();
		UUID uuid = player.getUUID();
		ServerGamePacketListenerImpl listener = player.connection;
		ServerCommonPacketListenerImplAccessor common = (ServerCommonPacketListenerImplAccessor) listener;
		Connection connection = common.lodecore$connection();

		byte[] data = node.playerData().write(player);
		byte[] state = ClientState.capture(node, player);

		// Bars and waypoints are known to the client by this node's ids for them; the next node
		// shows its own. A bar made with /bossbar keeps the player, as it does a player who logs
		// out: which players are on it is the cluster's, not this node's.
		for (ServerBossEvent bar : BossBars.showing(player)) {
			if (bar instanceof CustomBossEvent custom) {
				custom.onPlayerDisconnect(player);
			} else {
				bar.removePlayer(player);
			}
		}

		((ServerWaypointManagerAccessor) level.getWaypointManager()).lodecore$connections().row(player).values().removeIf(waypoint -> {
			waypoint.disconnect();
			return true;
		});

		// The client hears the last of this node, and then this node hangs up.
		((HandoffConnection) connection).lodecore$detach();
		common.lodecore$setConnection(new MirrorConnection());
		((HandoffConnection) connection).lodecore$hangUpWhenSent();

		// The player stays, as the mirror of one homed elsewhere. Nobody is told they left.
		PlayerListAccessor players = (PlayerListAccessor) server.getPlayerList();
		players.lodecore$players().remove(player);
		players.lodecore$playersByUUID().remove(uuid, player);
		players.lodecore$stats().remove(uuid);
		players.lodecore$advancements().remove(uuid);
		player.getAdvancements().clearTriggers();
		// It went with the player.
		player.inventoryMenu.setCarried(ItemStack.EMPTY);
		node.entities().demote(player);

		for (Object tracked : ((ChunkMapAccessor) level.getChunkSource().chunkMap).lodecore$entityMap().values()) {
			((TrackedEntityAccessor) tracked).lodecore$seenBy().remove(listener);
		}

		player.setChunkTrackingView(ChunkTrackingView.EMPTY);
		((PlayerChunkSenderAccessor) listener.chunkSender).lodecore$pendingChunks().clear();
		level.getChunkSource().move(player);
		level.updateSleepingPlayerList();
		node.playerData().onHandedOff(uuid);
		node.onHandedOff(player);
		node.send(Wire.handoffState(uuid, data, state));
		LOGGER.info("Let go of {}", player.getPlainTextName());
	}

	// ---- the node a player arrives at ----

	public void onPrepare(Wire.HandoffPrepare prepare) {
		ServerLevel level = node.level(prepare.dimension());

		if (level == null) {
			LOGGER.warn("Asked to take over {} in a dimension this node does not have", prepare.uuid());
			node.send(Wire.handoffAbort(prepare.uuid()));
			return;
		}

		Incoming previous = incoming.remove(prepare.uuid());

		if (previous != null) {
			forget(previous);
		}

		int radius = node.server().getPlayerList().getViewDistance() + HELD_BEYOND_VIEW;
		Incoming arriving = new Incoming(prepare.uuid(), prepare.token(), level, new ChunkPos(prepare.chunkX(), prepare.chunkZ()), radius);
		incoming.put(prepare.uuid(), arriving);
		level.getChunkSource().addTicketWithRadius(PREPARING, arriving.center, radius);
	}

	/** Whether a login presenting {@code token} moves a player here. */
	public boolean expects(UUID uuid, long token) {
		Incoming arriving = incoming.get(uuid);
		return arriving != null && arriving.token == token;
	}

	/** The proxy logged a player on their way here in. Their connection waits for them. */
	public boolean park(GameProfile profile, long token, Connection connection) {
		Incoming arriving = incoming.get(profile.id());

		if (arriving == null || arriving.token != token || arriving.connection != null) {
			return false;
		}

		arriving.connection = connection;
		arriving.profile = profile;
		return true;
	}

	/** Follows the players on their way here, and says when this node is ready for them. */
	private void prepare() {
		for (Iterator<Incoming> it = incoming.values().iterator(); it.hasNext(); ) {
			Incoming arriving = it.next();

			if (arriving.connection != null && !arriving.connection.isConnected()) {
				LOGGER.info("The proxy's connection for {} closed before they arrived", arriving.uuid);
				it.remove();
				forget(arriving);
				node.send(Wire.handoffAbort(arriving.uuid));
				continue;
			}

			ServerPlayer mirror = node.entities().remotePlayerMirror(arriving.uuid);

			if (mirror != null && mirror.level() == arriving.level && !mirror.chunkPosition().equals(arriving.center)) {
				arriving.level.getChunkSource().addTicketWithRadius(PREPARING, mirror.chunkPosition(), arriving.radius);
				arriving.level.getChunkSource().removeTicketWithRadius(PREPARING, arriving.center, arriving.radius);
				arriving.center = mirror.chunkPosition();
			}

			if (!arriving.ready && arriving.connection != null && caughtUp(arriving)) {
				arriving.ready = true;
				LOGGER.info("Ready for {}", arriving.uuid);
				node.send(Wire.handoffReady(arriving.uuid));
			}
		}

		int now = node.server().getTickCount();

		for (Iterator<Held> it = lingering.iterator(); it.hasNext(); ) {
			Held held = it.next();

			if (now >= held.until()) {
				held.level().getChunkSource().removeTicketWithRadius(PREPARING, held.center(), held.radius());
				it.remove();
			}
		}
	}

	/**
	 * Whether every chunk the player's client can have is ready to send here and matches its owner's
	 * copy. One that became ready only after they arrived would be sent to them again, though their
	 * client has it.
	 */
	private boolean caughtUp(Incoming arriving) {
		ChunkTrackingView view = ChunkTrackingView.of(arriving.center, arriving.radius - HELD_BEYOND_VIEW);
		ChunkMap chunks = arriving.level.getChunkSource().chunkMap;
		boolean[] ready = {true};
		view.forEach(pos -> {
			if (ready[0] && (chunks.getChunkToSend(pos.pack()) == null || !node.blocks().isSynced(arriving.level, pos))) {
				ready[0] = false;
			}
		});
		return ready[0];
	}

	public void onArrive(Wire.HandoffArrive arrive) {
		Incoming arriving = incoming.remove(arrive.uuid());

		if (arriving == null || arriving.connection == null || arriving.profile == null || !arriving.connection.isConnected()) {
			LOGGER.warn("{} arrived, but there is no connection for them here", arrive.uuid());

			if (arriving != null) {
				forget(arriving);
			}

			node.send(Wire.handoffAbort(arrive.uuid()));
			return;
		}

		lingering.add(new Held(arriving.level, arriving.center, arriving.radius, node.server().getTickCount() + LINGER_TICKS));

		try {
			promote(arriving, toArray(arrive.data()), toArray(arrive.state()));
			node.send(Wire.handoffDone(arrive.uuid()));
		} catch (IOException | RuntimeException e) {
			LOGGER.error("Could not take over {}", arriving.profile.name(), e);
			node.send(Wire.handoffAbort(arrive.uuid()));
			arriving.connection.disconnect(Component.literal("Could not move you to another server. Please reconnect."));
		}
	}

	private void promote(Incoming arriving, byte[] dataBytes, byte[] stateBytes) throws IOException {
		MinecraftServer server = node.server();
		CompoundTag state = ClientState.read(stateBytes);
		CompoundTag data = node.playerData().parse(arriving.uuid, dataBytes);
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(state.getStringOr("Dimension", "")));
		ServerLevel level = server.getLevel(dimension);
		int id = state.getIntOr("EntityId", 0);

		if (level == null || data == null) {
			throw new IOException("the player's data or dimension is missing");
		}

		// Spectators are not published, so there may be no mirror of them yet.
		ServerPlayer player = node.entities().remotePlayerMirror(arriving.uuid);

		if (player != null && player.level() != level) {
			node.entities().dropMirror(player);
			player = null;
		}

		if (player == null) {
			Vec3 position = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data)
					.read("Pos", Vec3.CODEC)
					.orElseThrow(() -> new IOException("the player's data has no position"));
			player = node.entities().mirrorRemotePlayer(level, arriving.profile, position, id);
		}

		if (!EntityIds.reassign(level, player, id)) {
			throw new IOException("another entity here has the player's id " + id);
		}

		ServerGamePacketListenerImpl listener = player.connection;
		Connection connection = arriving.connection;
		node.entities().promote(player);
		((ServerCommonPacketListenerImplAccessor) listener).lodecore$setConnection(connection);
		connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess())));
		connection.setupInboundProtocol(GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess()), listener), listener);
		listener.suspendFlushing();

		try {
			player.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));
			ClientState.restorePlayer(node, player, state);

			PlayerListAccessor players = (PlayerListAccessor) server.getPlayerList();
			players.lodecore$players().add(player);
			players.lodecore$playersByUUID().put(player.getUUID(), player);
			players.lodecore$stats().put(player.getUUID(), player.getStats());
			players.lodecore$advancements().put(player.getUUID(), player.getAdvancements());
			player.initInventoryMenu();
			ClientState.restoreView(node, player, state);

			for (ServerBossEvent bar : BossBars.showing(player)) {
				if (bar.isVisible()) {
					listener.send(ClientboundBossEventPacket.createAddPacket(bar));
				}
			}

			// The bars made with /bossbar the player is on, as for a player who logs in.
			server.getCustomBossEvents().onPlayerConnect(player);

			// Waypoints this node thought the mirror was shown, the client was not.
			((ServerWaypointManagerAccessor) level.getWaypointManager()).lodecore$connections().row(player).clear();
			level.getWaypointManager().updatePlayer(player);

			// What each node keeps of its own: the time, the border and the player's permissions.
			listener.send(new ClientboundInitializeBorderPacket(level.getWorldBorder()));
			listener.send(server.clockManager().createFullSyncPacket());
			server.getPlayerList().sendPlayerPermissionLevel(player);

			if (player.getChatSession() != null) {
				server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
						java.util.EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT), List.of(player)));
			}

			level.getChunkSource().move(player);
			listener.resetPosition();
			node.playerData().onHandedIn(player);
		} finally {
			listener.resumeFlushing();
		}

		LOGGER.info("{} arrived from another node", player.getPlainTextName());
	}

	private static byte[] toArray(ByteBuffer buffer) {
		byte[] array = new byte[buffer.remaining()];
		buffer.duplicate().get(array);
		return array;
	}

	// ---- either ----

	public void onCancel(Wire.HandoffCancel cancel) {
		// Asked while the player may be being let go of: they are not, now.
		if (outgoing.remove(cancel.uuid()) != null) {
			LOGGER.info("Keeping {} after all", cancel.uuid());
			node.send(Wire.handoffAbort(cancel.uuid()));
		}

		Incoming arriving = incoming.remove(cancel.uuid());

		if (arriving != null) {
			forget(arriving);
		}
	}

	private void forget(Incoming arriving) {
		arriving.level.getChunkSource().removeTicketWithRadius(PREPARING, arriving.center, arriving.radius);

		if (arriving.connection != null && arriving.connection.isConnected()) {
			arriving.connection.disconnect(Component.literal("Moving you to this server was called off."));
		}
	}

	/** Runs at the end of each tick, once its changes have gone out. */
	public void tick() {
		cutReady();
		prepare();
	}

	/** Without lodestar, nobody moves anywhere. */
	public void onDisconnected() {
		outgoing.clear();

		for (Incoming arriving : incoming.values()) {
			forget(arriving);
		}

		incoming.clear();
	}
}
