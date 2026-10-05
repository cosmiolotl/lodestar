package dev.lodecore.replication;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.lodecore.Node;
import dev.lodecore.net.Wire;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Custody: lets a player use what another node is the authority for.
 *
 * <p>Whatever a player does to an entity or a block entity has to be done by the object's
 * authority, or two nodes could both hand out what is in it. When a player homed here acts on
 * an object this node is not the authority for (opens a chest, trades with a villager, picks up an
 * item), the action is held back and this node <em>claims</em> the object through lodestar. The
 * object's owner hands over the object's exact state, lodestar tells every node that this one now
 * has custody, and the action is then done here, as on a single server: the player and the object
 * are both this node's. Once nobody here has used the object for a moment, its state is handed
 * back. lodestar gives custody of an object to one node at a time, so two players on different
 * nodes can never both take the same item out of the same chest.
 *
 * <p>The owner's side: it answers a claim at the end of the tick it heard it in, after its own last
 * changes to the object have gone out, and turns it down while one of its own players is using
 * the object.
 *
 * <p>Everything here runs on the game thread.
 */
public final class Custody {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/custody");

	/** How long an object is kept after it was last used, before it is handed back. */
	private static final int IDLE_TICKS = 40;
	/** How long to wait for lodestar to answer a claim. */
	private static final int CLAIM_TIMEOUT_TICKS = 200;
	/**
	 * How long, after handing an object back, before it is claimed again just because a player
	 * touches it. A player standing on an item they have no room for would otherwise claim it
	 * over and over.
	 */
	private static final int PASSIVE_COOLDOWN_TICKS = 40;
	/** Within this distance, a player with a menu open is taken to be using a block entity. */
	private static final double MENU_REACH = 8.0;

	// What an object's state, as handed over or back, starts with.
	private static final byte STATE = 1;
	private static final byte GONE = 2;

	private final Node node;
	/** Which node has custody of which entities, as lodestar has said, by dimension. */
	private final Map<ResourceKey<Level>, Map<UUID, Integer>> entityHolders = new HashMap<>();
	/** Which node has custody of which block entities, by dimension and packed position. */
	private final Map<ResourceKey<Level>, Long2IntMap> blockHolders = new HashMap<>();

	private int nextClaim = 1;
	/** Claims lodestar has yet to answer, by claim number. */
	private final Int2ObjectMap<Claim> claims = new Int2ObjectOpenHashMap<>();
	/** Objects being claimed, so that an object is only claimed once at a time. */
	private final Map<ResourceKey<Level>, Map<ObjectKey, Action>> claiming = new HashMap<>();
	/** Objects this node has custody of. */
	private final Map<ResourceKey<Level>, Map<ObjectKey, Held>> held = new HashMap<>();
	/** Claims this node, as an owner, has to answer at the end of the tick. */
	private final List<Wire.ClaimRequest> requests = new ArrayList<>();
	/** When objects were last handed back, for {@link #PASSIVE_COOLDOWN_TICKS}. */
	private final Map<ObjectKey, Integer> handedBack = new HashMap<>();

	private record Claim(ServerLevel level, ObjectKey key, Action action) {
	}

	/** Something a player wants done, waiting for custody of every object it needs. */
	private static final class Action {
		final ServerPlayer player;
		final Runnable run;
		final @Nullable Runnable onDenied;
		final int expiresAt;
		final Set<ObjectKey> waiting = new HashSet<>();
		boolean denied;

		Action(ServerPlayer player, Runnable run, @Nullable Runnable onDenied, int expiresAt) {
			this.player = player;
			this.run = run;
			this.onDenied = onDenied;
			this.expiresAt = expiresAt;
		}
	}

	private static final class Held {
		int lastUsed;
		/** Where the object last was, to hand it back to that chunk's owner if it is gone. */
		long chunk;
		boolean handingBack;

		Held(int now, long chunk) {
			this.lastUsed = now;
			this.chunk = chunk;
		}
	}

	public Custody(Node node) {
		this.node = node;
	}

	// ---- who has custody ----

	/** The node with custody of an entity, or {@link Node#NO_NODE} if its region's owner has it. */
	public int holderOf(ServerLevel level, Entity entity) {
		return holderOf(level, entity.getUUID());
	}

	public int holderOf(ServerLevel level, UUID entity) {
		Map<UUID, Integer> holders = entityHolders.get(level.dimension());
		return holders == null || holders.isEmpty() ? Node.NO_NODE : holders.getOrDefault(entity, Node.NO_NODE);
	}

	/** The node with custody of the block entity at a position, or {@link Node#NO_NODE}. */
	public int holderOf(ServerLevel level, BlockPos pos) {
		Long2IntMap holders = blockHolders.get(level.dimension());
		return holders == null ? Node.NO_NODE : holders.get(pos.asLong());
	}

	private int holderOf(ServerLevel level, ObjectKey key) {
		return key.isEntity() ? holderOf(level, key.entity()) : holderOf(level, key.blockPos());
	}

	private boolean isAuthority(ServerLevel level, ObjectKey key) {
		if (key.isEntity()) {
			Entity entity = level.getEntity(key.entity());
			return entity == null || node.entities().isAuthority(level, entity);
		}

		return node.blockEntities().isAuthority(level, key.blockPos());
	}

	// ---- the claimant's side ----

	/**
	 * Does something a player wants done to one or more objects: right away, if this node is the
	 * authority for all of them, or else once it has custody of them.
	 *
	 * @param passive whether the player merely touched the object, rather than meant to use it
	 * @param onDenied run instead, if custody cannot be had
	 * @return whether the action was held back, so that the caller must not do it now
	 */
	public boolean withCustody(ServerPlayer player, ServerLevel level, List<ObjectKey> keys, boolean passive, Runnable action, @Nullable Runnable onDenied) {
		if (!node.isConnected()) {
			return false;
		}

		int now = node.server().getTickCount();
		List<ObjectKey> missing = new ArrayList<>();

		for (ObjectKey key : keys) {
			if (isAuthority(level, key)) {
				Held mine = held.getOrDefault(level.dimension(), Map.of()).get(key);

				if (mine != null && !passive) {
					mine.lastUsed = now;
				}
			} else {
				missing.add(key);
			}
		}

		if (missing.isEmpty()) {
			return false;
		}

		Map<ObjectKey, Action> pending = claiming.computeIfAbsent(level.dimension(), dimension -> new HashMap<>());

		for (ObjectKey key : missing) {
			int holder = holderOf(level, key);

			// Already being claimed: the first claim's action is the one that runs.
			if (pending.containsKey(key)) {
				return true;
			}

			if (holder != Node.NO_NODE && holder != node.nodeId()) {
				if (!passive) {
					inUseElsewhere(player);

					if (onDenied != null) {
						onDenied.run();
					}
				}

				return true;
			}

			Integer back = handedBack.get(key);

			if (passive && back != null && now - back < PASSIVE_COOLDOWN_TICKS) {
				return true;
			}
		}

		Action waiting = new Action(player, action, onDenied, now + CLAIM_TIMEOUT_TICKS);
		int dimension = node.dimensionId(level.dimension());

		for (ObjectKey key : missing) {
			long chunk = chunkOf(level, key);

			if (chunk == Long.MIN_VALUE || dimension < 0) {
				return true;
			}

			int claim = nextClaim++;
			claims.put(claim, new Claim(level, key, waiting));
			pending.put(key, waiting);
			waiting.waiting.add(key);
			node.send(Wire.claim(claim, dimension, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), key.toBytes()));
		}

		return true;
	}

	/** Whether something a player wants done waits for custody. */
	public boolean isWaitingFor(ServerPlayer player) {
		for (Claim claim : claims.values()) {
			if (claim.action().player == player) {
				return true;
			}
		}

		return false;
	}

	private static long chunkOf(ServerLevel level, ObjectKey key) {
		if (key.isEntity()) {
			Entity entity = level.getEntity(key.entity());
			return entity == null ? Long.MIN_VALUE : entity.chunkPosition().pack();
		}

		return ChunkPos.pack(key.blockPos());
	}

	private static void inUseElsewhere(ServerPlayer player) {
		player.sendSystemMessage(Component.literal("Someone on another server is using that").withStyle(ChatFormatting.GRAY), true);
	}

	public void onClaimResult(Wire.ClaimResult result) {
		Claim claim = claims.remove(result.claim());

		if (claim == null) {
			return;
		}

		Map<ObjectKey, Action> pending = claiming.get(claim.level().dimension());

		if (pending != null) {
			pending.remove(claim.key(), claim.action());
		}

		Action action = claim.action();

		if (result.granted()) {
			int now = node.server().getTickCount();
			adopt(claim.level(), claim.key(), result.payload());
			held.computeIfAbsent(claim.level().dimension(), dimension -> new HashMap<>())
					.put(claim.key(), new Held(now, chunkOf(claim.level(), claim.key())));
		} else {
			action.denied = true;
		}

		action.waiting.remove(claim.key());

		if (action.waiting.isEmpty()) {
			finish(action);
		}
	}

	private void finish(Action action) {
		if (action.denied) {
			if (action.player.isAlive() && !action.player.hasDisconnected()) {
				inUseElsewhere(action.player);
			}

			if (action.onDenied != null) {
				action.onDenied.run();
			}

			return;
		}

		// The player may have left, or moved on, while the claim was out.
		if (action.player.hasDisconnected() || action.player.isRemoved() || node.entities().isRemotePlayer(action.player)) {
			return;
		}

		try {
			action.run.run();
		} catch (RuntimeException e) {
			LOGGER.warn("A player's action failed once custody arrived", e);
		}
	}

	/**
	 * Takes over an object's state, handed over by its owner or handed back to this owner. An
	 * empty payload means the holder left without handing anything back: what this node has
	 * stands. An object handed back as gone was used up while held (an item picked up, a boat
	 * broken), and this node's copy goes too.
	 */
	private void adopt(ServerLevel level, ObjectKey key, ByteBuffer payload) {
		if (!payload.hasRemaining()) {
			return;
		}

		try {
			FriendlyByteBuf in = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));

			if (in.readByte() == GONE) {
				if (key.isEntity()) {
					node.entities().forget(level, key.entity());
				}

				return;
			}

			CompoundTag tag = in.readNbt();

			if (tag == null) {
				return;
			}

			if (key.isEntity()) {
				node.entities().adopt(level, key.entity(), tag);
			} else {
				node.blockEntities().adopt(level, key.blockPos(), tag);
			}
		} catch (IndexOutOfBoundsException | DecoderException e) {
			LOGGER.warn("Malformed object state: {}", e.toString());
		}
	}

	/** An object's state, or that it is gone if there is none. */
	private static byte[] writeState(@Nullable CompoundTag tag) {
		FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
		out.writeByte(tag == null ? GONE : STATE);

		if (tag != null) {
			out.writeNbt(tag);
		}

		return ByteBufUtil.getBytes(out);
	}

	// ---- everyone's side ----

	public void onCustody(Wire.Custody custody) {
		ServerLevel level = node.level(custody.dimension());
		ObjectKey key;

		try {
			key = ObjectKey.fromBytes(custody.object());
		} catch (IllegalArgumentException e) {
			LOGGER.warn("lodestar named a malformed object: {}", e.getMessage());
			return;
		}

		if (level == null) {
			return;
		}

		ResourceKey<Level> dimension = level.dimension();
		int holder = custody.holder();

		if (key.isEntity()) {
			Map<UUID, Integer> holders = entityHolders.computeIfAbsent(dimension, d -> new HashMap<>());

			if (holder == Node.NO_NODE) {
				holders.remove(key.entity());
			} else {
				holders.put(key.entity(), holder);
			}

			// Whoever now ticks the entity starts from where it is.
			Entity entity = level.getEntity(key.entity());

			if (entity != null) {
				entity.needsSync = true;
			}
		} else {
			Long2IntMap holders = blockHolders.computeIfAbsent(dimension, d -> new Long2IntOpenHashMap());

			if (holder == Node.NO_NODE) {
				holders.remove(key.block());
			} else {
				holders.put(key.block(), holder);
			}
		}

		if (holder != node.nodeId()) {
			Map<ObjectKey, Held> mine = held.get(dimension);

			if (mine != null) {
				mine.remove(key);
			}
		}
	}

	// ---- the owner's side ----

	public void onClaimRequest(Wire.ClaimRequest request) {
		requests.add(request);
	}

	/** An object this node owns the region of is back, with the state its holder left it in. */
	public void onReleased(Wire.Released released) {
		ServerLevel level = node.level(released.dimension());

		if (level == null) {
			return;
		}

		try {
			adopt(level, ObjectKey.fromBytes(released.object()), released.payload());
		} catch (IllegalArgumentException e) {
			LOGGER.warn("An object came back malformed: {}", e.getMessage());
		}
	}

	/**
	 * At the end of the tick: answers the claims heard this tick, hands back what has not been
	 * used for a while, and gives up on claims lodestar never answered.
	 */
	public void flush() {
		int now = node.server().getTickCount();
		answerClaims();

		for (Map.Entry<ResourceKey<Level>, Map<ObjectKey, Held>> entry : held.entrySet()) {
			ServerLevel level = node.server().getLevel(entry.getKey());
			int dimension = node.dimensionId(entry.getKey());

			if (level == null || dimension < 0) {
				continue;
			}

			for (Map.Entry<ObjectKey, Held> object : entry.getValue().entrySet()) {
				handBackIfIdle(level, dimension, object.getKey(), object.getValue(), now);
			}
		}

		for (Iterator<Int2ObjectMap.Entry<Claim>> it = claims.int2ObjectEntrySet().iterator(); it.hasNext(); ) {
			Claim claim = it.next().getValue();

			if (now > claim.action().expiresAt) {
				it.remove();
				claiming.getOrDefault(claim.level().dimension(), Map.of()).remove(claim.key(), claim.action());
				claim.action().denied = true;
				claim.action().waiting.remove(claim.key());

				if (claim.action().waiting.isEmpty()) {
					finish(claim.action());
				}
			}
		}

		handedBack.values().removeIf(at -> now - at > PASSIVE_COOLDOWN_TICKS);
	}

	private void answerClaims() {
		for (Wire.ClaimRequest request : requests) {
			ServerLevel level = node.level(request.dimension());
			CompoundTag state = null;

			try {
				ObjectKey key = ObjectKey.fromBytes(request.object());
				state = level == null ? null : handOver(level, key);
			} catch (IllegalArgumentException e) {
				LOGGER.warn("Node #{} claimed a malformed object: {}", request.claimant(), e.getMessage());
			}

			node.send(Wire.claimAnswer(request.claim(), request.claimant(), state != null, state == null ? new byte[0] : writeState(state)));
		}

		requests.clear();
	}

	/** The state of an object this node can hand over, or null if it cannot. */
	private @Nullable CompoundTag handOver(ServerLevel level, ObjectKey key) {
		if (holderOf(level, key) != Node.NO_NODE) {
			return null;
		}

		if (key.isEntity()) {
			Entity entity = level.getEntity(key.entity());

			if (entity == null || entity instanceof Player || !node.entities().isAuthority(level, entity) || inUse(entity)) {
				return null;
			}

			return node.entities().saveWhole(level, entity);
		}

		BlockPos pos = key.blockPos();
		BlockEntity blockEntity = level.getBlockEntity(pos);

		if (blockEntity == null || !node.blockEntities().isAuthority(level, pos) || inUse(level, blockEntity)) {
			return null;
		}

		return blockEntity.saveWithFullMetadata(level.registryAccess());
	}

	private void handBackIfIdle(ServerLevel level, int dimension, ObjectKey key, Held mine, int now) {
		if (mine.handingBack) {
			return;
		}

		CompoundTag state = null;

		if (key.isEntity()) {
			Entity entity = level.getEntity(key.entity());

			if (entity != null && !entity.isRemoved()) {
				mine.chunk = entity.chunkPosition().pack();

				if (inUse(entity)) {
					mine.lastUsed = now;
				}

				if (now - mine.lastUsed < IDLE_TICKS) {
					return;
				}

				state = node.entities().saveWhole(level, entity);
			}
		} else {
			BlockEntity blockEntity = level.getBlockEntity(key.blockPos());

			if (blockEntity != null) {
				if (inUse(level, blockEntity)) {
					mine.lastUsed = now;
				}

				if (now - mine.lastUsed < IDLE_TICKS) {
					return;
				}

				state = blockEntity.saveWithFullMetadata(level.registryAccess());
			}
		}

		// Kept until lodestar confirms, so that it is handed back only once.
		mine.handingBack = true;
		handedBack.put(key, now);
		node.send(Wire.release(dimension, ChunkPos.getX(mine.chunk), ChunkPos.getZ(mine.chunk), key.toBytes(), writeState(state)));
	}

	// ---- whether one of this node's players is using an object ----

	private List<ServerPlayer> localPlayers() {
		return node.server().getPlayerList().getPlayers();
	}

	/** Riding it, carrying it, trading with it, holding it on a lead, or looking into it. */
	private boolean inUse(Entity entity) {
		for (ServerPlayer player : localPlayers()) {
			if (entity.hasIndirectPassenger(player) || player.hasIndirectPassenger(entity)) {
				return true;
			}

			if (entity instanceof Merchant merchant && merchant.getTradingPlayer() == player) {
				return true;
			}

			if (entity instanceof Leashable leashable && leashable.getLeashHolder() == player) {
				return true;
			}

			if (entity instanceof Container container && hasOpen(player, container)) {
				return true;
			}
		}

		return false;
	}

	private boolean inUse(ServerLevel level, BlockEntity blockEntity) {
		for (ServerPlayer player : localPlayers()) {
			if (player.level() != level || player.containerMenu == player.inventoryMenu) {
				continue;
			}

			if (blockEntity instanceof Container container && hasOpen(player, container)) {
				return true;
			}

			// Menus that do not show the block entity's own slots, such as a beacon's or a
			// lectern's, are taken to be its when open close by.
			if (!(blockEntity instanceof Container) && player.distanceToSqr(Vec3.atCenterOf(blockEntity.getBlockPos())) < MENU_REACH * MENU_REACH) {
				return true;
			}
		}

		return false;
	}

	private static boolean hasOpen(ServerPlayer player, Container container) {
		if (player.containerMenu == player.inventoryMenu) {
			return false;
		}

		for (Slot slot : player.containerMenu.slots) {
			if (slot.container == container || slot.container instanceof CompoundContainer compound && compound.contains(container)) {
				return true;
			}
		}

		return false;
	}

	public void onDisconnected() {
		entityHolders.clear();
		blockHolders.clear();
		claims.clear();
		claiming.clear();
		held.clear();
		requests.clear();
		handedBack.clear();
	}
}
