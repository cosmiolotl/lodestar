package dev.lodecore.replication;

import dev.lodecore.Node;
import dev.lodecore.net.Wire;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import static dev.lodecore.replication.EntityReplication.ENTITIES;
import static dev.lodecore.replication.EntityWire.*;

final class EntityPublisher {

	private final Node node;
	private final EntityReplication replication;
	private final Int2ObjectMap<Published> published = new Int2ObjectOpenHashMap<>();
	private final Map<ResourceKey<Level>, Long2ObjectMap<Batch>> batches = new HashMap<>();
	private final Int2ObjectMap<List<Effect>> effects = new Int2ObjectOpenHashMap<>();
	EntityPublisher(Node node, EntityReplication replication) { this.node = node; this.replication = replication; }
	private static final class Batch {
		final RegistryFriendlyByteBuf records;
		int count;

		Batch(ServerLevel level) {
			records = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
		}
	}

	public void flush() {
		int now = node.server().getTickCount();
		for (var change : replication.dirty.take(now).entrySet()) {
			Entity entity = change.getKey();
			ServerLevel level = (ServerLevel) entity.level();
			if (!entity.isRemoved() && replication.isAuthority(level, entity) && node.dimensionId(level.dimension()) >= 0) publish(level, entity, now, change.getValue());
			else published.remove(entity.getId());
		}

		effects.clear();

		for (Map.Entry<ResourceKey<Level>, Long2ObjectMap<Batch>> entry : batches.entrySet()) {
			int dimension = node.dimensionId(entry.getKey());

			for (Long2ObjectMap.Entry<Batch> chunk : entry.getValue().long2ObjectEntrySet()) {
				Batch batch = chunk.getValue();

				if (dimension >= 0) {
					RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(6 + batch.records.readableBytes()), batch.records.registryAccess());
					out.writeByte(ENTITIES);
					out.writeVarInt(batch.count);
					out.writeBytes(batch.records);
					long pos = chunk.getLongKey();
					try { node.send(Wire.publish(dimension, ChunkPos.getX(pos), ChunkPos.getZ(pos), ByteBufUtil.getBytes(out))); }
					finally { out.release(); }
				}
				batch.records.release();
			}
		}

		batches.clear();
	}
	private void publish(ServerLevel level, Entity entity, int now, int fields) {
		Published last = published.get(entity.getId());
		long chunk = entity.chunkPosition().pack();

		// Spectators are not shown to other players.
		if (entity instanceof ServerPlayer player && player.isSpectator()) {
			if (last != null) {
				gone(level, last.chunk, last.uuid, REMOVED);
				published.remove(entity.getId());
			}

			return;
		}

		if (last != null && last.chunk != chunk) {
			// Nodes that do not have the new chunk loaded let their mirror go.
			gone(level, last.chunk, last.uuid, chunk);
		}

		if (!othersNeed(level, entity, chunk)) {
			published.remove(entity.getId());
			return;
		}

		int refresh = entity instanceof Player || entity instanceof ItemEntity ? FAST_REFRESH_TICKS : REFRESH_TICKS;

		if (last == null || last.chunk != chunk || now - last.refreshedAt >= refresh) {
			Published whole = new Published(entity.getUUID());

			if (!writeWhole(level, entity, whole)) {
				published.remove(entity.getId());
				return;
			}

			whole.chunk = chunk;
			whole.refreshedAt = now;
			published.put(entity.getId(), whole);
			replication.dirty.refreshed(entity, now);
		} else {
			writeChanges(level, entity, last, fields);
		}

		List<Effect> shown = effects.get(entity.getId());

		if (shown != null) {
			for (Effect effect : shown) {
				RegistryFriendlyByteBuf out = record(level, chunk, EFFECT, entity.getUUID());
				writeEffect(out, effect);
			}
		}
	}

	private boolean othersNeed(ServerLevel level, Entity entity, long chunk) {
		return !node.owns(level, chunk) || node.isNeededElsewhere(level, chunk);
	}

	private boolean writeWhole(ServerLevel level, Entity entity, Published into) {
		long chunk = entity.chunkPosition().pack();
		Move move = Move.of(entity);

		if (entity instanceof ServerPlayer player) {
			RegistryFriendlyByteBuf out = record(level, chunk, PLAYER, entity.getUUID());
			out.writeVarInt(entity.getId());
			ByteBufCodecs.GAME_PROFILE.encode(out, player.getGameProfile());
			move.write(out);
			writeData(out, player.getEntityData().getNonDefaultValues());
			writeEquipment(out, player, null, into);
		} else {
			CompoundTag tag = save(level, entity);

			if (tag == null) {
				return false;
			}

			RegistryFriendlyByteBuf out = record(level, chunk, FULL, entity.getUUID());
			out.writeVarInt(entity.getId());
			out.writeNbt(tag);

			if (entity instanceof LivingEntity living) {
				// Taken for comparison only: the whole entity already carries its equipment.
				for (int i = 0; i < SLOTS.length; i++) {
					into.equipment[i] = living.getItemBySlot(SLOTS[i]).copy();
				}
			}
		}

		// Everything the entity's data says went out just now.
		((ReplicatedData) entity.getEntityData()).lodecore$takeChanged();
		remember(into, move);
		into.vehicle = vehicleOf(entity);

		if (into.vehicle != null) {
			writeRide(record(level, chunk, RIDE, entity.getUUID()), into.vehicle);
		}

		return true;
	}

	private void writeChanges(ServerLevel level, Entity entity, Published last, int fields) {
		long chunk = last.chunk;
		Move move = Move.of(entity);

		if (moved(last, move)) {
			move.write(record(level, chunk, MOVE, entity.getUUID()));
			remember(last, move);
		}

		List<SynchedEntityData.DataValue<?>> changed = (fields & EntityReplication.METADATA) == 0
				? List.of() : ((ReplicatedData) entity.getEntityData()).lodecore$takeChanged();

		if (!changed.isEmpty()) {
			writeData(record(level, chunk, DATA, entity.getUUID()), changed);
		}

		if ((fields & EntityReplication.EQUIPMENT) != 0 && entity instanceof LivingEntity living && equipmentChanged(living, last)) {
			writeEquipment(record(level, chunk, EQUIPMENT, entity.getUUID()), living, last, last);
		}

		UUID vehicle = vehicleOf(entity);

		if (!java.util.Objects.equals(vehicle, last.vehicle)) {
			writeRide(record(level, chunk, RIDE, entity.getUUID()), vehicle);
			last.vehicle = vehicle;
		}
	}

	private RegistryFriendlyByteBuf record(ServerLevel level, long chunk, int kind, UUID uuid) {
		Batch batch = batches.computeIfAbsent(level.dimension(), key -> new Long2ObjectOpenHashMap<>())
				.computeIfAbsent(chunk, key -> new Batch(level));
		batch.count++;
		batch.records.writeByte(kind);
		batch.records.writeUUID(uuid);
		return batch.records;
	}

	private void gone(ServerLevel level, long chunk, UUID uuid, long destination) {
		record(level, chunk, GONE, uuid).writeLong(destination);
	}

	public void onEntityUnload(ServerLevel level, Entity entity) {
		Published last = published.remove(entity.getId());
		effects.remove(entity.getId());
		Entity.RemovalReason reason = entity.getRemovalReason();
		// Something taken into custody and used up at once (an item picked up) was never published
		// from here, but everyone else has a copy of it.
		boolean held = node.isConnected() && node.custody().holderOf(level, entity) == node.nodeId();

		// An authority that unloads a chunk is the last node to have it loaded; nobody is left to tell.
		if ((last != null || held) && node.isConnected() && reason != null && reason != Entity.RemovalReason.UNLOADED_TO_CHUNK) {
			gone(level, last != null ? last.chunk : entity.chunkPosition().pack(), entity.getUUID(), REMOVED);
		}

	}

	public void onEntityPacket(ServerLevel level, Entity entity, Packet<?> packet) {
		if (!node.isConnected() || replication.applyingRemote || !replication.isAuthority(level, entity)) {
			return;
		}

		Effect effect = switch (packet) {
			case ClientboundSwingAnimationPacket swing -> new Swing(swing.hand(), swing.animation());
			case ClientboundEntityEventPacket event -> new Event(event.getEventId());
			case ClientboundAnimatePacket animate -> new Animate(animate.getAction());
			case ClientboundDamageEventPacket damage -> new Damage(damage.getSource(level));
			// Movement, data and equipment travel as records of their own.
			default -> null;
		};

		if (effect != null) {
			effects.computeIfAbsent(entity.getId(), key -> new ArrayList<>(2)).add(effect);
			replication.dirty.changed(entity);
		}
	}
	void forget(Entity entity) { published.remove(entity.getId()); effects.remove(entity.getId()); }
	void clear() {
		published.clear(); effects.clear();
		for (var chunks : batches.values()) for (Batch batch : chunks.values()) batch.records.release();
		batches.clear();
	}

}
