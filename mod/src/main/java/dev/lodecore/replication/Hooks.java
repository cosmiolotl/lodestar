package dev.lodecore.replication;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.authlib.GameProfile;
import dev.lodecore.Node;
import dev.lodecore.mixin.CompoundContainerAccessor;
import dev.lodecore.net.Wire;
import dev.lodecore.shared.GlobalChat;
import dev.lodecore.shared.MapSync;
import dev.lodecore.shared.SharedData;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Where the mixins reach the running node. Does nothing while no server is running. */
public final class Hooks {
	private static volatile Node node;

	private Hooks() {
	}

	public static void attach(Node node) {
		Hooks.node = node;
	}

	public static void detach() {
		Hooks.node = null;
	}

	/** The node, if the caller is on its game thread. Ownership is only tracked there. */
	private static @Nullable Node onGameThread(Level level) {
		Node node = Hooks.node;
		return node != null && level instanceof ServerLevel && node.server().isSameThread() ? node : null;
	}

	private static @Nullable Node onGameThread() {
		Node node = Hooks.node;
		return node != null && node.server().isSameThread() ? node : null;
	}

	/** Called on the game thread at the very start of each server tick. */
	public static void beforeServerTick(MinecraftServer server) {
		Node node = Hooks.node;

		if (node != null && node.server() == server) {
			node.beforeTick();
		}
	}

	// ---- what is ticked where ----

	/** Whether this node is the one that ticks a chunk: the owner of its region. */
	public static boolean mayTick(ServerLevel level, long chunkPos) {
		Node node = onGameThread(level);
		return node == null || node.owns(level, chunkPos);
	}

	/** Whether this node ticks an entity: whether its copy is the truth, rather than a mirror. */
	public static boolean mayTick(Entity entity) {
		Node node = onGameThread(entity.level());
		return node == null || node.entities().isAuthority((ServerLevel) entity.level(), entity);
	}

	/**
	 * For a position whose block entity is in some node's custody, whether this node ticks its
	 * block entity and runs its block events: if it is the holder. Null for any other position,
	 * which goes by who owns its chunk.
	 */
	public static @Nullable Boolean mayTickBlockEntityAt(Level level, BlockPos pos) {
		Node node = onGameThread(level);

		if (node == null || !node.isConnected()) {
			return null;
		}

		int holder = node.custody().holderOf((ServerLevel) level, pos);

		if (holder == Node.NO_NODE) {
			return null;
		}

		return holder == node.nodeId() && ((TickRange) level).lodecore$inTickRange(ChunkPos.pack(pos));
	}

	// ---- blocks and block entities ----

	public static int adjustFlags(Level level, BlockPos pos, int flags) {
		Node node = onGameThread(level);
		return node == null ? flags : node.blocks().adjustFlags((ServerLevel) level, pos, flags);
	}

	/** Whether a block may not be changed here, because another node has custody of its block entity. */
	public static boolean isLocked(Level level, BlockPos pos) {
		Node node = onGameThread(level);
		return node != null && node.blocks().isLocked((ServerLevel) level, pos);
	}

	public static void onBlockChanged(Level level, BlockPos pos, BlockState state) {
		Node node = onGameThread(level);

		if (node != null) {
			node.blocks().onBlockChanged((ServerLevel) level, pos, state);
		}
	}

	public static void onBlockEntityChanged(BlockEntity blockEntity) {
		Level level = blockEntity.getLevel();
		Node node = level == null ? null : onGameThread(level);

		if (node != null) {
			node.blockEntities().onChanged(blockEntity);
		}
	}

	public static void onBlockEntityMade(Level level, BlockEntity blockEntity) {
		Node node = onGameThread(level);

		if (node != null) {
			node.blockEntities().onMade((ServerLevel) level, blockEntity);
		}
	}

	/**
	 * Hides from hoppers, droppers and crafters a container this node is not the authority
	 * for: what is in it is not this node's to move.
	 */
	public static @Nullable Container filterContainer(Level level, @Nullable Container container) {
		Node node = onGameThread(level);

		if (node == null || container == null || !node.isConnected()) {
			return container;
		}

		ServerLevel serverLevel = (ServerLevel) level;
		return isAuthority(node, serverLevel, container) ? container : null;
	}

	private static boolean isAuthority(Node node, ServerLevel level, Container container) {
		return switch (container) {
			case BlockEntity blockEntity -> node.blockEntities().isAuthority(level, blockEntity.getBlockPos());
			case Entity entity -> node.entities().isAuthority(level, entity);
			case CompoundContainer compound -> isAuthority(node, level, ((CompoundContainerAccessor) compound).lodecore$first())
					&& isAuthority(node, level, ((CompoundContainerAccessor) compound).lodecore$second());
			default -> true;
		};
	}

	/** Whether there is a block entity at a position that this node is not the authority for. */
	public static boolean isForeignBlockEntity(Level level, BlockPos pos) {
		Node node = onGameThread(level);
		return node != null && node.isConnected() && level.getBlockEntity(pos) != null
				&& !node.blockEntities().isAuthority((ServerLevel) level, pos);
	}

	/** Leaves out of what a hopper can pick up the items this node is not the authority for. */
	public static List<ItemEntity> filterItems(Level level, List<ItemEntity> items) {
		Node node = onGameThread(level);

		if (node == null || !node.isConnected()) {
			return items;
		}

		return items.stream().filter(item -> node.entities().isAuthority((ServerLevel) level, item)).toList();
	}

	/** Whether nothing here may pick up an item: true for a mirror, which only its authority hands out. */
	public static boolean isUntouchable(ItemEntity item) {
		Node node = onGameThread(item.level());
		return node != null && !node.entities().isAuthority((ServerLevel) item.level(), item);
	}

	// ---- entities ----

	/** @return whether the entity is kept out of the level, because another node owns where it is */
	public static boolean interceptNewEntity(ServerLevel level, Entity entity) {
		Node node = onGameThread(level);
		return node != null && node.entities().interceptNewEntity(level, entity);
	}

	public static void setSpawning(ServerLevel level, boolean spawning) {
		Node node = onGameThread(level);

		if (node != null) {
			node.entities().setSpawning(spawning);
		}
	}

	/** @return whether the damage was sent to the entity's authority rather than to be done here */
	public static boolean forwardHurt(LivingEntity target, ServerLevel level, DamageSource source, float amount) {
		Node node = onGameThread(level);
		return node != null && node.entities().forwardHurt(target, level, source, amount);
	}

	/** @return whether damage to a dragon's part was sent to the dragon's authority rather than to be done here */
	public static boolean forwardDragonHurt(EnderDragon dragon, ServerLevel level, EnderDragonPart part, DamageSource source, float amount) {
		Node node = onGameThread(level);
		return node != null && node.entities().forwardHurt(dragon, level, part, source, amount);
	}

	public static void onEntityPacket(ServerLevel level, Entity entity, Packet<?> packet) {
		Node node = onGameThread(level);

		if (node != null) {
			node.entities().onEntityPacket(level, entity, packet);
		}
	}

	/** Whether an entity is a mirror: another node is its authority. */
	public static boolean isMirror(Entity entity) {
		Node node = onGameThread(entity.level());
		return node != null && node.isConnected() && !node.entities().isAuthority((ServerLevel) entity.level(), entity);
	}

	/** Whether a player is the mirror of one homed on another node. */
	public static boolean isRemotePlayer(ServerPlayer player) {
		Node node = onGameThread(player.level());
		return node != null && node.entities().isRemotePlayer(player);
	}

	/** Whether a player is a remote player standing where this node is not the owner. */
	public static boolean isRemotePlayerElsewhere(ServerLevel level, ServerPlayer player) {
		Node node = onGameThread(level);
		return node != null && node.entities().isRemotePlayerElsewhere(level, player);
	}

	// ---- what players do ----

	/** @return what to answer instead, if the interaction waits for custody */
	public static @Nullable InteractionResult interact(Player player, Entity target, InteractionHand hand, Vec3 location) {
		Node node = onGameThread(player.level());
		return node == null ? null : node.interactions().interact(player, target, hand, location);
	}

	/** @return whether the attack waits for custody */
	public static boolean attack(Player player, Entity target) {
		Node node = onGameThread(player.level());
		return node != null && node.interactions().attack(player, target);
	}

	/** @return what to answer instead, if the use waits for custody */
	public static @Nullable InteractionResult useItemOn(ServerPlayer player, Level level, InteractionHand hand, BlockHitResult hit) {
		Node node = onGameThread(level);
		return node == null ? null : node.interactions().useItemOn(player, (ServerLevel) level, hand, hit);
	}

	/** @return whether the breaking waits for custody */
	public static boolean destroyBlock(ServerPlayer player, BlockPos pos) {
		Node node = onGameThread(player.level());
		return node != null && node.interactions().destroyBlock(player, pos);
	}

	/** @return whether the edit waits for custody */
	public static boolean updateSignText(SignBlockEntity sign, Player player, SignTextSlot slot, List<FilteredText> lines) {
		Node node = onGameThread(player.level());
		return node != null && node.interactions().updateSignText(sign, player, slot, lines);
	}

	/** @return whether the pickup is not to happen now */
	public static boolean touch(Entity touched, Player player) {
		Node node = onGameThread(touched.level());
		return node != null && node.interactions().touch(touched, player);
	}

	// ---- entity ids and moving players ----

	/** The next entity id, from this node's own block. Called on any thread. */
	public static int nextEntityId(AtomicInteger vanilla) {
		Node node = Hooks.node;
		return node == null ? vanilla.incrementAndGet() : node.entityIds().next();
	}

	/** Whether a login presenting {@code token} moves a player here from another node. */
	public static boolean expectsHandoff(UUID uuid, long token) {
		Node node = onGameThread();
		return node != null && node.handoffs().expects(uuid, token);
	}

	/** Keeps the connection of a player on their way here, until they arrive. */
	public static boolean parkHandoffLogin(GameProfile profile, long token, Connection connection) {
		Node node = onGameThread();
		return node != null && node.handoffs().park(profile, token, connection);
	}

	// ---- the state kept once for all dimensions ----

	/** Whether this node runs the weather cycle: whether it is the master of the cluster's state. */
	public static boolean advancesWeather() {
		Node node = onGameThread();
		return node == null || node.worldState().isMaster(Wire.CLUSTER_SCOPE);
	}

	/** Something in the cluster's state changed here: one of the parts {@link WorldState} names. */
	public static void onWorldStateChanged(int part) {
		Node node = onGameThread();

		if (node != null) {
			node.worldState().onChanged(Wire.CLUSTER_SCOPE, part);
		}
	}

	public static void onClockChanged(Holder<WorldClock> clock) {
		Node node = onGameThread();

		if (node != null) {
			node.worldState().onClockChanged(clock);
		}
	}

	public static void onGameRuleChanged(GameRule<?> rule) {
		Node node = onGameThread();

		if (node != null) {
			node.worldState().onGameRuleChanged(rule);
		}
	}

	// ---- the rest of the cluster's data ----

	/** The scoreboard, boss bars, command storage and the like, or null if not on the game thread. */
	public static @Nullable SharedData shared() {
		Node node = onGameThread();
		return node == null ? null : node.shared();
	}

	/** Chat and the game's announcements, heard on every node; null if not on the game thread. */
	public static @Nullable GlobalChat chat() {
		Node node = onGameThread();
		return node == null ? null : node.chat();
	}

	public static @Nullable MapSync maps() {
		Node node = onGameThread();
		return node == null ? null : node.maps();
	}

	public static @Nullable RaidSync raids() {
		Node node = onGameThread();
		return node == null ? null : node.raids();
	}

	public static @Nullable DragonFightSync dragonFights() {
		Node node = onGameThread();
		return node == null ? null : node.dragonFights();
	}

	/** The id for a new map, or null to take the server's own next one. */
	public static @Nullable MapId nextMapId() {
		MapSync maps = maps();
		return maps == null ? null : maps.nextId();
	}

	public static void onMapLookup(MapId id, @Nullable MapItemSavedData map) {
		MapSync maps = maps();

		if (maps != null) {
			maps.onLookup(id, map);
		}
	}

	public static void onMapSet(MapId id, MapItemSavedData map) {
		MapSync maps = maps();

		if (maps != null) {
			maps.onSet(id, map);
		}
	}

	// ---- players' data ----

	/** Whether a joining player's data has arrived from lodestar, or does not need to. */
	public static boolean isPlayerDataReady(UUID uuid) {
		Node node = onGameThread();
		return node == null || node.playerData().isReady(uuid);
	}

	/** The data lodestar lent for a joining player, or null to load the node's own save. */
	public static @Nullable CompoundTag lentPlayerData(UUID uuid) {
		Node node = onGameThread();
		return node == null ? null : node.playerData().load(uuid);
	}

	public static void onPlayerSaved(Player player) {
		Node node = onGameThread();

		if (node != null && player instanceof ServerPlayer serverPlayer && !node.entities().isRemotePlayer(serverPlayer)) {
			node.playerData().onSaved(serverPlayer);
		}
	}

	public static void onPlayerRemoved(ServerPlayer player) {
		Node node = onGameThread();

		if (node != null) {
			node.playerData().onRemoved(player);
		}
	}
}
