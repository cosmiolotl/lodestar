package dev.lodecore.replication;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import dev.lodecore.Node;
import dev.lodecore.mixin.RaidAccessor;
import dev.lodecore.mixin.RaidsAccessor;
import dev.lodecore.net.Wire;
import dev.lodecore.shared.CounterBlocks;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/**
 * Raids, each run by the node that simulates the village it is in: the owner of the region of
 * the raid's center, which simulates its raiders and villagers too. Every other node that has the
 * center loaded keeps a copy of the raid that it does not run, so that it shows its players near
 * the raid the raid's bar, plays them the raid's horn, and lets its own players' bad omen turn
 * into a raid omen as it would on a single server.
 *
 * <p>At the end of each tick, the node that runs a raid publishes it to the raid's center chunk,
 * and every other node there takes it on. When the village's region changes owner, the new owner
 * runs the raid from the copy it has, with the raiders it now simulates.
 *
 * <p>A player's raid omen starts a raid on the player's own node. If another node simulates where
 * the raid would be, the player's node passes the omen on to it, and that node starts or extends
 * the raid. A raid's id comes from blocks lodestar hands each node, so that raiders, which know
 * their raid by its id, find it on every node. The heroes of a won raid on other nodes are
 * rewarded by their own nodes.
 *
 * <p>Everything here runs on the game thread.
 */
public final class RaidSync {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/raids");

	// Payloads.
	/** Published to a raid's center by the node that runs it: the raid, or that it is over. */
	public static final int RAIDS = 20;
	/** To the node that simulates where a player's raid omen starts a raid. */
	private static final int RAID_START = 21;
	/** To the node of a player who is a hero of a won raid. */
	private static final int RAID_HERO = 22;

	/** How often a raid is published even if nothing about it changed. */
	private static final int REFRESH_TICKS = 100;
	private static final Codec<Raid> CODEC = Raid.MAP_CODEC.codec();
	/** What becomes of a raid, which is only reachable by reflection: its type is private. */
	private static final Field STATUS = statusField();

	private final Node node;
	private final CounterBlocks ids = new CounterBlocks(Wire.COUNTER_RAID_IDS);
	/** What this node last published of each raid it runs, by dimension and raid id. */
	private final Map<ServerLevel, Int2ObjectMap<Published>> published = new HashMap<>();
	/** Where each raid run here sounded its horn this tick. */
	private final Map<Raid, BlockPos> horns = new IdentityHashMap<>();

	private record Published(long chunk, CompoundTag state) {
	}

	public RaidSync(Node node) {
		this.node = node;
	}

	private static Field statusField() {
		try {
			Field field = Raid.class.getDeclaredField("status");
			field.setAccessible(true);
			return field;
		} catch (NoSuchFieldException e) {
			throw new IllegalStateException("Raid has no status", e);
		}
	}

	public static boolean handlesDirect(int kind) {
		return kind == RAID_START || kind == RAID_HERO;
	}

	private static long centerChunk(Raid raid) {
		return ChunkPos.pack(raid.getCenter());
	}

	/** Whether this node runs a raid: whether it simulates the raid's center. */
	public boolean runsHere(ServerLevel level, Raid raid) {
		return node.owns(level, centerChunk(raid));
	}

	// ---- raids here ----

	/**
	 * Ticks a raid if this node runs it. A copy is not run, but shows its bar to the players here
	 * who are in it, and is dropped once this node no longer has the raid's center: it would not
	 * hear of the raid any more, and has it back from the node that runs it if it loads the center
	 * again.
	 */
	public void tick(ServerLevel level, Raid raid) {
		if (runsHere(level, raid)) {
			raid.tick(level);
		} else if (!level.hasChunkAt(raid.getCenter())) {
			// Taken out of the level's raids on its next tick.
			raid.stop();
		} else if (level.getGameTime() % 20 == 0) {
			((RaidAccessor) raid).lodecore$updatePlayers(level);
		}
	}

	/** A raid run here sounded its horn. */
	public void onHorn(Raid raid, BlockPos origin) {
		horns.put(raid, origin);
	}

	/**
	 * The id for a new raid, from this node's own blocks, or -1 to take the level's own next id: cut
	 * off from lodestar, or before lodestar has handed this node a block.
	 */
	public int nextId(Raids raids) {
		if (!node.isConnected()) {
			return -1;
		}

		RaidsAccessor accessor = (RaidsAccessor) raids;
		int id = ids.next(accessor.lodecore$nextId() + 1);

		if (id >= 0) {
			accessor.lodecore$setNextId(id);
		}

		return id;
	}

	public void onIdBlock(int block) {
		ids.onBlock(block);
	}

	/**
	 * A raid omen ran out on a player here, which starts or extends a raid where they were. If
	 * another node simulates that place, the omen is passed on to it.
	 *
	 * @return whether it was passed on, rather than to be taken here
	 */
	public boolean forwardOmen(ServerPlayer player, BlockPos pos) {
		ServerLevel level = player.level();
		int owner = node.ownerOf(level.dimension(), ChunkPos.pack(pos));

		if (!node.isConnected() || player.isSpectator() || owner == Node.NO_NODE || owner == node.nodeId() || !canStart(level, pos)) {
			return false;
		}

		MobEffectInstance omen = player.getEffect(MobEffects.RAID_OMEN);
		Raid raid = level.getRaidAt(pos);

		// The player's side of absorbing the omen, which their own node keeps.
		if (omen != null && (raid == null || !raid.hasFirstWaveSpawned())) {
			player.awardStat(Stats.RAID_TRIGGER);
			CriteriaTriggers.RAID_OMEN.trigger(player);
		}

		RegistryFriendlyByteBuf out = direct(level, RAID_START);
		out.writeBlockPos(pos);
		out.writeVarInt(omen == null ? -1 : omen.getAmplifier());
		node.send(Wire.direct(owner, ByteBufUtil.getBytes(out)));
		return true;
	}

	private static boolean canStart(ServerLevel level, BlockPos pos) {
		return level.getGameRules().get(GameRules.RAIDS) && level.environmentAttributes().getValue(EnvironmentAttributes.CAN_START_RAID, pos);
	}

	/** As {@link Raids#createOrExtendRaid}, for a player whose node passed on their raid omen. */
	private void start(ServerLevel level, BlockPos pos, int amplifier) {
		if (!canStart(level, pos)) {
			return;
		}

		List<PoiRecord> village = level.getPoiManager().getInRange(poi -> poi.is(PoiTypeTags.VILLAGE), pos, 64, PoiManager.Occupancy.IS_OCCUPIED).toList();
		BlockPos center = pos;

		if (!village.isEmpty()) {
			Vec3 total = Vec3.ZERO;

			for (PoiRecord poi : village) {
				total = total.add(poi.getPos().getX(), poi.getPos().getY(), poi.getPos().getZ());
			}

			center = BlockPos.containing(total.scale(1.0 / village.size()));
		}

		Raids raids = level.getRaids();
		RaidsAccessor accessor = (RaidsAccessor) raids;
		Raid raid = accessor.lodecore$getOrCreateRaid(level, center);

		if (!raid.isStarted() && !accessor.lodecore$raidMap().containsValue(raid)) {
			accessor.lodecore$raidMap().put(accessor.lodecore$getUniqueId(), raid);
		}

		if (amplifier >= 0 && (!raid.isStarted() || raid.getRaidOmenLevel() < raid.getMaxRaidOmenLevel())) {
			raid.setRaidOmenLevel(Mth.clamp(raid.getRaidOmenLevel() + amplifier + 1, 0, raid.getMaxRaidOmenLevel()));
		}

		raids.setDirty();
	}

	/**
	 * Gives a hero of a won raid their reward. A player on another node is rewarded there.
	 *
	 * @return whether the effect was given here, as {@link LivingEntity#addEffect} answers
	 */
	public boolean reward(LivingEntity hero, MobEffectInstance effect) {
		if (hero instanceof ServerPlayer player && node.entities().isRemotePlayer(player)) {
			int home = node.homeOf(player.getUUID());

			if (home != Node.NO_NODE) {
				RegistryFriendlyByteBuf out = direct(player.level(), RAID_HERO);
				out.writeUUID(player.getUUID());
				MobEffectInstance.STREAM_CODEC.encode(out, effect);
				node.send(Wire.direct(home, ByteBufUtil.getBytes(out)));
			}

			return true;
		}

		return hero.addEffect(effect);
	}

	/** Whether a hero is a player on another node, whose stats and advancements are kept there. */
	public boolean isRewardedElsewhere(ServerPlayer hero) {
		return node.entities().isRemotePlayer(hero);
	}

	// ---- payloads ----

	private RegistryFriendlyByteBuf direct(ServerLevel level, int kind) {
		RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), node.server().registryAccess());
		out.writeByte(kind);
		out.writeVarInt(node.dimensionId(level.dimension()));
		return out;
	}

	private DynamicOps<Tag> ops() {
		return node.server().registryAccess().createSerializationContext(NbtOps.INSTANCE);
	}

	/**
	 * At the end of a tick: publishes each raid this node runs that another node has the center
	 * of, and that a raid it ran is over.
	 */
	public void flush() {
		if (!node.isConnected()) {
			horns.clear();
			return;
		}

		if (ids.shouldAsk()) {
			int floor = 0;

			for (ServerLevel level : node.server().getAllLevels()) {
				floor = Math.max(floor, ((RaidsAccessor) level.getRaids()).lodecore$nextId() + 1);
			}

			node.send(ids.ask(floor));
		}

		boolean refresh = node.server().getTickCount() % REFRESH_TICKS == 0;

		for (ServerLevel level : node.server().getAllLevels()) {
			int dimension = node.dimensionId(level.dimension());

			if (dimension >= 0) {
				flush(level, dimension, refresh);
			}
		}

		horns.clear();
	}

	private void flush(ServerLevel level, int dimension, boolean refresh) {
		Int2ObjectMap<Raid> raids = ((RaidsAccessor) level.getRaids()).lodecore$raidMap();
		Int2ObjectMap<Published> mine = published.computeIfAbsent(level, key -> new Int2ObjectOpenHashMap<>());

		// The map's entries are views of it, which do not survive a removal: go by the ids.
		for (int id : new IntArrayList(mine.keySet())) {
			Raid raid = raids.get(id);

			if (raid == null) {
				// Over. Where it was, this node still simulates, or the new owner ends it.
				publish(level, dimension, mine.remove(id).chunk(), id, null);
			} else if (!runsHere(level, raid)) {
				mine.remove(id);
			}
		}

		for (Int2ObjectMap.Entry<Raid> entry : raids.int2ObjectEntrySet()) {
			Raid raid = entry.getValue();
			long chunk = centerChunk(raid);

			if (!runsHere(level, raid) || !node.hasLoaded(level, chunk)) {
				continue;
			}

			if (!node.isNeededElsewhere(level, chunk)) {
				// Nobody else has it: whoever loads it next gets it as soon as it is published.
				mine.remove(entry.getIntKey());
				continue;
			}

			CompoundTag state = write(raid);

			if (state == null) {
				continue;
			}

			Published last = mine.get(entry.getIntKey());
			BlockPos horn = horns.get(raid);

			if (refresh || horn != null || last == null || last.chunk() != chunk || !last.state().equals(state)) {
				mine.put(entry.getIntKey(), new Published(chunk, state.copy()));

				if (horn != null) {
					state.putLong("horn", horn.asLong());
				}

				publish(level, dimension, chunk, entry.getIntKey(), state);
			}
		}
	}

	private @Nullable CompoundTag write(Raid raid) {
		try {
			RaidAccessor accessor = (RaidAccessor) raid;
			ServerBossEvent bar = accessor.lodecore$raidEvent();
			CompoundTag tag = new CompoundTag();
			tag.put("raid", CODEC.encodeStart(ops(), raid).getOrThrow());
			tag.putInt("celebration", accessor.lodecore$celebrationTicks());
			tag.put("name", ComponentSerialization.CODEC.encodeStart(ops(), bar.getName()).getOrThrow());
			tag.putFloat("progress", bar.getProgress());
			tag.putBoolean("visible", bar.isVisible());
			return tag;
		} catch (RuntimeException e) {
			LOGGER.warn("Could not write a raid at {}: {}", raid.getCenter(), e.toString());
			return null;
		}
	}

	/** Publishes a raid, or that it is over: {@code state} null. */
	private void publish(ServerLevel level, int dimension, long chunk, int id, @Nullable CompoundTag state) {
		if (!node.hasLoaded(level, chunk)) {
			return;
		}

		RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), node.server().registryAccess());
		out.writeByte(RAIDS);
		out.writeVarInt(id);
		out.writeBoolean(state != null);

		if (state != null) {
			out.writeNbt(state);
		}

		node.send(Wire.publish(dimension, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), ByteBufUtil.getBytes(out)));
	}

	/** The node that runs a raid published it. */
	public void onRelay(int from, ServerLevel level, ByteBuffer payload) {
		try {
			RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload), node.server().registryAccess());
			in.readByte();
			int id = in.readVarInt();
			CompoundTag state = in.readBoolean() ? in.readNbt() : null;
			Raids raids = level.getRaids();
			Int2ObjectMap<Raid> all = ((RaidsAccessor) raids).lodecore$raidMap();
			Raid raid = all.get(id);

			if (state == null) {
				if (raid != null && !runsHere(level, raid)) {
					raid.stop();
					all.remove(id);
					raids.setDirty();
				}

				return;
			}

			Raid taken = CODEC.parse(ops(), state.getCompoundOrEmpty("raid")).getOrThrow();

			if (raid == null) {
				all.put(id, taken);
				raid = taken;
			} else if (runsHere(level, raid)) {
				// Published by the node that ran it before this one, a tick ago.
				return;
			} else {
				take(raid, taken);
			}

			RaidAccessor accessor = (RaidAccessor) raid;
			accessor.lodecore$setCelebrationTicks(state.getIntOr("celebration", 0));
			ServerBossEvent bar = accessor.lodecore$raidEvent();
			Component name = ComponentSerialization.CODEC.parse(ops(), state.get("name")).result().orElse(bar.getName());

			if (!name.equals(bar.getName())) {
				bar.setName(name);
			}

			bar.setProgress(state.getFloatOr("progress", 0));
			bar.setVisible(state.getBooleanOr("visible", false));
			state.getLong("horn").ifPresent(horn -> accessor.lodecore$playSound(level, BlockPos.of(horn)));
			raids.setDirty();
		} catch (IndexOutOfBoundsException | DecoderException | IllegalArgumentException | IllegalStateException e) {
			LOGGER.warn("Node #{} sent a malformed raid: {}", from, e.toString());
		}
	}

	/** Takes on another copy of a raid, keeping the raiders this node knows are in it. */
	private static void take(Raid raid, Raid taken) {
		RaidAccessor to = (RaidAccessor) raid;
		RaidAccessor from = (RaidAccessor) taken;
		to.lodecore$setStarted(taken.isStarted());
		to.lodecore$setActive(taken.isActive());
		to.lodecore$setTicksActive(from.lodecore$ticksActive());
		raid.setRaidOmenLevel(taken.getRaidOmenLevel());
		to.lodecore$setGroupsSpawned(taken.getGroupsSpawned());
		to.lodecore$setRaidCooldownTicks(from.lodecore$raidCooldownTicks());
		to.lodecore$setPostRaidTicks(from.lodecore$postRaidTicks());
		to.lodecore$setTotalHealth(taken.getTotalHealth());
		to.lodecore$setNumGroups(from.lodecore$numGroups());
		to.lodecore$setCenter(taken.getCenter());
		to.lodecore$heroesOfTheVillage().clear();
		to.lodecore$heroesOfTheVillage().addAll(from.lodecore$heroesOfTheVillage());

		try {
			STATUS.set(raid, STATUS.get(taken));
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Another node sent this node something about a raid. */
	public void onDirect(int from, ByteBuffer payload) {
		try {
			RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload), node.server().registryAccess());
			int kind = in.readByte();
			ServerLevel level = node.level(in.readVarInt());

			if (level == null) {
				return;
			}

			switch (kind) {
				case RAID_START -> start(level, in.readBlockPos(), in.readVarInt());
				case RAID_HERO -> {
					UUID uuid = in.readUUID();
					MobEffectInstance effect = MobEffectInstance.STREAM_CODEC.decode(in);
					ServerPlayer hero = node.server().getPlayerList().getPlayer(uuid);

					// As a raid won here would, for a hero who is here.
					if (hero != null && !hero.isSpectator()) {
						hero.addEffect(effect);
						hero.awardStat(Stats.RAID_WIN);
						CriteriaTriggers.RAID_WIN.trigger(hero);
					}
				}
				default -> LOGGER.warn("Node #{} sent an unknown payload {}", from, kind);
			}
		} catch (IndexOutOfBoundsException | DecoderException | IllegalArgumentException e) {
			LOGGER.warn("Node #{} sent a malformed payload: {}", from, e.toString());
		}
	}

	public void onDisconnected() {
		published.clear();
		horns.clear();
		ids.onDisconnected();
	}
}
