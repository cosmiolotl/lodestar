package dev.lodecore.replication;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.DynamicOps;
import dev.lodecore.Node;
import dev.lodecore.mixin.EnderDragonFightAccessor;
import dev.lodecore.net.Wire;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.end.EnderDragonFight;

/**
 * The dragon fight in the End, run by the node that simulates the middle of the End: the owner of
 * the region of the fight's origin, which simulates the dragon, the crystals and the exit portal
 * too. Every other node with players in the End keeps a copy of the fight that it does not run:
 * it shows its players near the fight the dragon's bar, and keeps the middle of the End loaded for
 * them, as the game does, which brings it into the cluster's use.
 *
 * <p>At the end of each tick, the node that runs the fight publishes it to the origin's chunk,
 * and every other node there takes it on. What another node's players do to the fight, placing
 * the last crystal on the exit portal or killing the dragon where another node simulates it, is
 * passed on to the node that runs it.
 *
 * <p>Everything here runs on the game thread.
 */
public final class DragonFightSync {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/dragon");

	// Payloads.
	/** Published to the fight's origin by the node that runs it. */
	public static final int DRAGON_FIGHT = 23;
	/** To the node that runs the fight: something another node's players did to it. */
	private static final int DRAGON_ACTION = 24;

	// Actions.
	private static final int TRY_RESPAWN = 1;
	private static final int DRAGON_KILLED = 2;

	/** How often the fight is published even if nothing about it changed. */
	private static final int REFRESH_TICKS = 100;
	/** As the game's own fight does. */
	private static final int PLAYER_SCAN_TICKS = 20;
	private static final int ARENA_TICKET_RADIUS = 9;

	private final Node node;
	/** What this node last published of the fight in each level it runs it in. */
	private final Map<ServerLevel, CompoundTag> published = new HashMap<>();

	public DragonFightSync(Node node) {
		this.node = node;
	}

	public static boolean handlesDirect(int kind) {
		return kind == DRAGON_ACTION;
	}

	private static long originChunk(EnderDragonFight fight) {
		return ChunkPos.pack(((EnderDragonFightAccessor) fight).lodecore$origin());
	}

	private static ServerLevel levelOf(EnderDragonFight fight) {
		return ((EnderDragonFightAccessor) fight).lodecore$level();
	}

	/** Whether this node runs a fight: whether it simulates the fight's origin. */
	public boolean runsHere(EnderDragonFight fight) {
		return node.owns(levelOf(fight), originChunk(fight));
	}

	// ---- the fight here ----

	/**
	 * Ticks the copy of a fight this node does not run: as the game's fight does, it shows its bar
	 * to the players near it and keeps the arena loaded while they are, and that is all.
	 */
	public void tickCopy(EnderDragonFight fight) {
		EnderDragonFightAccessor accessor = (EnderDragonFightAccessor) fight;
		ServerBossEvent bar = accessor.lodecore$dragonEvent();
		bar.setVisible(!accessor.lodecore$dragonKilled());
		int scan = accessor.lodecore$ticksSinceLastPlayerScan() + 1;

		if (scan >= PLAYER_SCAN_TICKS) {
			accessor.lodecore$updatePlayers();
			scan = 0;
		}

		accessor.lodecore$setTicksSinceLastPlayerScan(scan);
		ChunkPos arena = new ChunkPos(0, 0);

		if (bar.getPlayers().isEmpty()) {
			levelOf(fight).getChunkSource().removeTicketWithRadius(TicketType.DRAGON, arena, ARENA_TICKET_RADIUS);
		} else {
			levelOf(fight).getChunkSource().addTicketWithRadius(TicketType.DRAGON, arena, ARENA_TICKET_RADIUS);
		}
	}

	/**
	 * A player here placed the last crystal on the exit portal, or a dragon died here, in a fight
	 * another node runs: passes it on to that node.
	 *
	 * @return whether it was passed on, rather than to be done here
	 */
	public boolean forward(EnderDragonFight fight, int action, @Nullable UUID dragon) {
		if (runsHere(fight)) {
			return false;
		}

		ServerLevel level = levelOf(fight);
		int runner = node.ownerOf(level.dimension(), originChunk(fight));

		if (runner != Node.NO_NODE) {
			RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), node.server().registryAccess());
			out.writeByte(DRAGON_ACTION);
			out.writeVarInt(node.dimensionId(level.dimension()));
			out.writeByte(action);

			if (dragon != null) {
				out.writeUUID(dragon);
			}

			node.send(Wire.direct(runner, ByteBufUtil.getBytes(out)));
		}

		return true;
	}

	public boolean forwardRespawn(EnderDragonFight fight) {
		return forward(fight, TRY_RESPAWN, null);
	}

	public boolean forwardKill(EnderDragonFight fight, EnderDragon dragon) {
		return forward(fight, DRAGON_KILLED, dragon.getUUID());
	}

	// ---- payloads ----

	private DynamicOps<Tag> ops() {
		return node.server().registryAccess().createSerializationContext(NbtOps.INSTANCE);
	}

	/** At the end of a tick: publishes each fight this node runs that another node has the origin of. */
	public void flush() {
		if (!node.isConnected()) {
			return;
		}

		boolean refresh = node.server().getTickCount() % REFRESH_TICKS == 0;

		for (ServerLevel level : node.server().getAllLevels()) {
			EnderDragonFight fight = level.getDragonFight();
			int dimension = node.dimensionId(level.dimension());
			long chunk = fight == null ? 0 : originChunk(fight);

			if (fight == null || dimension < 0 || !runsHere(fight) || !node.hasLoaded(level, chunk) || !node.isNeededElsewhere(level, chunk)) {
				published.remove(level);
				continue;
			}

			// The fight hears of its dragon's health from the dragon, wherever that is simulated.
			EnderDragonFightAccessor accessor = (EnderDragonFightAccessor) fight;

			if (accessor.lodecore$dragonUUID() != null && level.getEntity(accessor.lodecore$dragonUUID()) instanceof EnderDragon dragon) {
				fight.updateDragon(dragon);
			}

			CompoundTag state = write(fight);

			if (state != null && (refresh || !state.equals(published.get(level)))) {
				published.put(level, state);
				RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), node.server().registryAccess());
				out.writeByte(DRAGON_FIGHT);
				out.writeNbt(state);
				node.send(Wire.publish(dimension, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), ByteBufUtil.getBytes(out)));
			}
		}
	}

	private @Nullable CompoundTag write(EnderDragonFight fight) {
		try {
			ServerBossEvent bar = ((EnderDragonFightAccessor) fight).lodecore$dragonEvent();
			CompoundTag tag = new CompoundTag();
			tag.put("fight", EnderDragonFight.CODEC.encodeStart(ops(), fight).getOrThrow());
			tag.put("name", ComponentSerialization.CODEC.encodeStart(ops(), bar.getName()).getOrThrow());
			tag.putFloat("progress", bar.getProgress());
			return tag;
		} catch (RuntimeException e) {
			LOGGER.warn("Could not write the dragon fight: {}", e.toString());
			return null;
		}
	}

	/** The node that runs a fight published it. */
	public void onRelay(int from, ServerLevel level, ByteBuffer payload) {
		EnderDragonFight fight = level.getDragonFight();

		if (fight == null || runsHere(fight)) {
			return;
		}

		try {
			RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload), node.server().registryAccess());
			in.readByte();
			CompoundTag state = in.readNbt();

			if (state == null) {
				return;
			}

			EnderDragonFight taken = EnderDragonFight.CODEC.parse(ops(), state.getCompoundOrEmpty("fight")).getOrThrow();
			EnderDragonFightAccessor to = (EnderDragonFightAccessor) fight;
			EnderDragonFightAccessor theirs = (EnderDragonFightAccessor) taken;
			to.lodecore$setNeedsStateScanning(theirs.lodecore$needsStateScanning());
			to.lodecore$setDragonKilled(theirs.lodecore$dragonKilled());
			to.lodecore$setPreviouslyKilled(theirs.lodecore$previouslyKilled());
			to.lodecore$setRespawnStage(theirs.lodecore$respawnStage());
			to.lodecore$setRespawnTime(theirs.lodecore$respawnTime());
			to.lodecore$setDragonUUID(theirs.lodecore$dragonUUID());
			to.lodecore$setExitPortalLocation(theirs.lodecore$exitPortalLocation());
			to.lodecore$gateways().clear();
			to.lodecore$gateways().addAll(theirs.lodecore$gateways());
			to.lodecore$setRespawnCrystals(theirs.lodecore$respawnCrystals());
			fight.setDirty();

			ServerBossEvent bar = to.lodecore$dragonEvent();
			Component name = ComponentSerialization.CODEC.parse(ops(), state.get("name")).result().orElse(bar.getName());

			if (!name.equals(bar.getName())) {
				bar.setName(name);
			}

			bar.setProgress(state.getFloatOr("progress", 0));
			bar.setVisible(!theirs.lodecore$dragonKilled());
		} catch (IndexOutOfBoundsException | DecoderException | IllegalArgumentException | IllegalStateException e) {
			LOGGER.warn("Node #{} sent a malformed dragon fight: {}", from, e.toString());
		}
	}

	/** Another node's players did something to the fight this node runs. */
	public void onDirect(int from, ByteBuffer payload) {
		try {
			RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload), node.server().registryAccess());
			in.readByte();
			ServerLevel level = node.level(in.readVarInt());
			int action = in.readByte();
			EnderDragonFight fight = level == null ? null : level.getDragonFight();

			if (fight == null || !runsHere(fight)) {
				return;
			}

			switch (action) {
				case TRY_RESPAWN -> fight.tryRespawn();
				case DRAGON_KILLED -> {
					UUID uuid = in.readUUID();

					// The fight only asks the dragon who it is; its mirror here may be gone already.
					if (level.getEntity(uuid) instanceof EnderDragon dragon) {
						fight.setDragonKilled(dragon);
					} else {
						EnderDragon stand = EntityTypes.ENDER_DRAGON.create(level, EntitySpawnReason.EVENT);

						if (stand != null) {
							stand.setUUID(uuid);
							fight.setDragonKilled(stand);
							stand.discard();
						}
					}
				}
				default -> LOGGER.warn("Node #{} sent an unknown dragon fight action {}", from, action);
			}
		} catch (IndexOutOfBoundsException | DecoderException | IllegalArgumentException e) {
			LOGGER.warn("Node #{} sent a malformed payload: {}", from, e.toString());
		}
	}

	public void onDisconnected() {
		published.clear();
	}
}
