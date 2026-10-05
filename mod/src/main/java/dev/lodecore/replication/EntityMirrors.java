package dev.lodecore.replication;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import dev.lodecore.Node;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.TagValueInput;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static dev.lodecore.replication.EntityPresentation.*;
import static dev.lodecore.replication.EntityWire.*;

final class EntityMirrors {

	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/entities");
	private final Node node;
	private final EntityReplication replication;
	private final RemotePlayers remotePlayers;
	EntityMirrors(Node node, EntityReplication replication, RemotePlayers players) {
		this.node = node; this.replication = replication; this.remotePlayers = players;
	}
	public void onRelay(int from, ServerLevel level, ChunkPos chunkPos, ByteBuffer payload) {
		RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload), level.registryAccess());
		replication.applyingRemote = true;

		try {
			in.readByte();
			int count = in.readVarInt();

			for (int i = 0; i < count; i++) {
				int kind = in.readByte();
				UUID uuid = in.readUUID();

				switch (kind) {
					case FULL -> {
						int id = in.readVarInt();
						CompoundTag tag = in.readNbt();

						if (tag != null && from == replication.recordAuthority(level, uuid, chunkPos)) {
							applyWhole(level, uuid, tag, id);
						}
					}
					case PLAYER -> {
						int id = in.readVarInt();
						GameProfile profile = ByteBufCodecs.GAME_PROFILE.decode(in);
						Move move = Move.read(in);
						List<SynchedEntityData.DataValue<?>> data = readData(in);
						List<Pair<EquipmentSlot, ItemStack>> equipment = readEquipment(in);

						if (from == node.homeOf(uuid) && profile.id().equals(uuid)) {
							ServerPlayer mirror = remotePlayers.update(level, profile, move, id);

							if (mirror != null) {
								applyData(mirror, data);
								applyEquipment(level, mirror, equipment);
							}
						}
					}
					case MOVE -> {
						Move move = Move.read(in);
						Entity mirror = mirror(level, uuid, from, chunkPos);

						if (mirror != null) {
							applyMove(level, mirror, move);
						}
					}
					case DATA -> {
						List<SynchedEntityData.DataValue<?>> data = readData(in);
						Entity mirror = mirror(level, uuid, from, chunkPos);

						if (mirror != null) {
							applyData(mirror, data);
						}
					}
					case EQUIPMENT -> {
						List<Pair<EquipmentSlot, ItemStack>> equipment = readEquipment(in);

						if (mirror(level, uuid, from, chunkPos) instanceof LivingEntity mirror) {
							applyEquipment(level, mirror, equipment);
						}
					}
					case EFFECT -> {
						Effect effect = readEffect(level, in);
						Entity mirror = mirror(level, uuid, from, chunkPos);

						if (mirror != null) {
							show(level, mirror, effect);
						}
					}
					case GONE -> {
						long destination = in.readLong();
						Entity mirror = mirror(level, uuid, from, chunkPos);

						if (mirror != null && (destination == REMOVED || !node.hasLoaded(level, destination))) {
							remove(mirror);
						}
					}
					case RIDE -> {
						UUID vehicle = in.readBoolean() ? in.readUUID() : null;
						Entity mirror = mirror(level, uuid, from, chunkPos);

						if (mirror != null) {
							ride(level, mirror, vehicle);
						}
					}
					default -> throw new DecoderException("unknown record " + kind);
				}
			}
		} catch (IndexOutOfBoundsException | DecoderException | IllegalArgumentException | IllegalStateException e) {
			LOGGER.warn("Node #{} published malformed entities: {}", from, e.toString());
		} finally {
			replication.applyingRemote = false;
		}
	}

	private @Nullable Entity mirror(ServerLevel level, UUID uuid, int from, ChunkPos chunkPos) {
		Entity entity = level.getEntity(uuid);

		if (entity == null || replication.isAuthority(level, entity)) {
			return null;
		}

		int authority = entity instanceof Player ? node.homeOf(uuid) : replication.recordAuthority(level, uuid, chunkPos);
		return from == authority ? entity : null;
	}

	private static void ride(ServerLevel level, Entity mirror, @Nullable UUID vehicleId) {
		Entity vehicle = vehicleId == null ? null : level.getEntity(vehicleId);

		if (vehicle == mirror.getVehicle()) {
			return;
		}

		if (vehicle == null) {
			mirror.stopRiding();
		} else {
			mirror.startRiding(vehicle, true, false);
		}
	}

	void adopt(ServerLevel level, UUID uuid, CompoundTag tag) {
		Entity existing = level.getEntity(uuid);
		replication.applyingRemote = true;

		try {
			if (existing != null) {
				if (!(existing instanceof Player)) {
					existing.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
					existing.needsSync = true;
				}

				return;
			}

			if (((EntityLookup) level).lodecore$hasEntity(uuid)) {
				return;
			}

			Entity created = EntityType.loadEntityRecursive(tag, level, new EntitySpawnRequest(EntitySpawnReason.LOAD, true), entity -> entity);

			if (created != null && created.getUUID().equals(uuid)) {
				((KnownEntity) created).lodecore$markKnown();
				level.addFreshEntity(created);
			}
		} finally {
			replication.applyingRemote = false;
		}
	}

	void forget(ServerLevel level, UUID uuid) {
		Entity entity = level.getEntity(uuid);

		if (entity != null && !(entity instanceof Player)) {
			entity.discard();
		}
	}

	void applyWhole(ServerLevel level, UUID uuid, CompoundTag tag, int id) {
		Entity existing = level.getEntity(uuid);

		if (existing != null) {
			if (existing instanceof Player || replication.isAuthority(level, existing)) {
				return;
			}

			if (EntityType.by(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag)).orElse(null) == existing.getType()) {
				existing.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
				existing.needsSync = true;
				// A copy this node had before it heard from the authority, from its own save. It
				// is the mirror now, rather than to be let go of as out of date.
				replication.staleEntities.remove(existing);
				((KnownEntity) existing).lodecore$markKnown();
				boolean sameId = EntityIds.reassign(level, existing, id);

				// A dragon cannot be given another id: its parts' ids follow its own. It is made
				// again instead, under its authority's.
				if (sameId || !(existing instanceof EnderDragon)) {
					return;
				}
			}

			existing.discard();
		} else if (((EntityLookup) level).lodecore$hasEntity(uuid)) {
			// It is here, in a chunk at the edge of what is loaded, where it cannot be reached
			// until the chunk is in use. It catches up then.
			return;
		}

		Entity created = EntityType.loadEntityRecursive(tag, level, new EntitySpawnRequest(EntitySpawnReason.LOAD, true), entity -> entity);

		if (created != null && created.getUUID().equals(uuid)) {
			((KnownEntity) created).lodecore$markKnown();

			if (EntityIds.isFree(level, id)) {
				setId(level, created, id);
			}

			level.addFreshEntity(created);
		}
	}

	private static void setId(ServerLevel level, Entity entity, int id) {
		entity.setId(id);

		if (entity instanceof EnderDragon dragon) {
			EnderDragonPart[] parts = dragon.getSubEntities();

			for (int i = 0; i < parts.length; i++) {
				if (EntityIds.isFree(level, id + 1 + i)) {
					parts[i].setId(id + 1 + i);
				}
			}
		}
	}

	private void remove(Entity mirror) {
		if (mirror instanceof ServerPlayer player) {
			remotePlayers.remove(player);
		} else {
			mirror.discard();
		}
	}
}
