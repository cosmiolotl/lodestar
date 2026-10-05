package dev.lodecore.replication;

import com.mojang.datafixers.util.Pair;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class EntityWire {
	// Records in an ENTITIES payload.
	static final int FULL = 1;
	static final int PLAYER = 2;
	static final int MOVE = 3;
	static final int DATA = 4;
	static final int EQUIPMENT = 5;
	static final int EFFECT = 6;
	static final int GONE = 7;
	static final int RIDE = 8;

	/** Where a GONE record says an entity went when it has left the world rather than the chunk. */
	static final long REMOVED = Long.MAX_VALUE;
	/**
	 * How often mirrors are sent the authority's whole copy of an entity, which carries what the
	 * other records do not: a mob's memories, a villager's trades, and the like.
	 */
	static final int REFRESH_TICKS = 100;
	/**
	 * How often items and players are sent whole. A player's mirror can only be made from a whole
	 * record, and an item can only be picked up once its pickup delay, which only the whole record
	 * carries, has run out.
	 */
	static final int FAST_REFRESH_TICKS = 20;
	/** An entity that is passed from owner to owner is given up after this many hops. */
	static final int MAX_SPAWN_HOPS = 3;

	static final EquipmentSlot[] SLOTS = EquipmentSlot.values();

	/** Where an entity is and where it is heading. */
	record Move(Vec3 position, float yRot, float xRot, float headRot, Vec3 motion, boolean onGround) {
		static Move of(Entity entity) {
			return new Move(entity.position(), entity.getYRot(), entity.getXRot(), entity.getYHeadRot(), entity.getDeltaMovement(), entity.onGround());
		}

		static Move read(RegistryFriendlyByteBuf in) {
			return new Move(
					new Vec3(in.readDouble(), in.readDouble(), in.readDouble()),
					in.readFloat(),
					in.readFloat(),
					in.readFloat(),
					new Vec3(in.readDouble(), in.readDouble(), in.readDouble()),
					in.readBoolean());
		}

		void write(RegistryFriendlyByteBuf out) {
			out.writeDouble(position.x);
			out.writeDouble(position.y);
			out.writeDouble(position.z);
			out.writeFloat(yRot);
			out.writeFloat(xRot);
			out.writeFloat(headRot);
			out.writeDouble(motion.x);
			out.writeDouble(motion.y);
			out.writeDouble(motion.z);
			out.writeBoolean(onGround);
		}
	}

	/** Something the game shows for an entity, which mirrors show too. */
	sealed interface Effect {
	}

	record Swing(InteractionHand hand, SwingAnimation animation) implements Effect {
	}

	record Event(byte id) implements Effect {
	}

	record Animate(int action) implements Effect {
	}

	record Damage(DamageSource source) implements Effect {
	}

	static @Nullable CompoundTag save(ServerLevel level, Entity entity) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());

		try {
			if (!entity.saveAsPassenger(output)) {
				return null;
			}
		} catch (RuntimeException e) {
			LOGGER.warn("Could not write down {} to replicate it: {}", entity, e.toString());
			return null;
		}

		CompoundTag tag = output.buildResult();
		// Passengers are entities of their own, and replicate as such.
		tag.remove("Passengers");
		return tag;
	}

	static @Nullable UUID vehicleOf(Entity entity) {
		Entity vehicle = entity.getVehicle();
		return vehicle == null ? null : vehicle.getUUID();
	}

	static void writeRide(RegistryFriendlyByteBuf out, @Nullable UUID vehicle) {
		out.writeBoolean(vehicle != null);

		if (vehicle != null) {
			out.writeUUID(vehicle);
		}
	}

	static boolean moved(Published last, Move move) {
		return last.position.distanceToSqr(move.position()) > 1.0E-8
				|| Math.abs(last.yRot - move.yRot()) > 0.01F
				|| Math.abs(last.xRot - move.xRot()) > 0.01F
				|| Math.abs(last.headRot - move.headRot()) > 0.01F
				|| last.motion.distanceToSqr(move.motion()) > 1.0E-8
				|| last.onGround != move.onGround();
	}

	static void remember(Published into, Move move) {
		into.position = move.position();
		into.yRot = move.yRot();
		into.xRot = move.xRot();
		into.headRot = move.headRot();
		into.motion = move.motion();
		into.onGround = move.onGround();
	}

	static boolean equipmentChanged(LivingEntity living, Published last) {
		for (int i = 0; i < SLOTS.length; i++) {
			if (!ItemStack.matches(last.equipment[i] == null ? ItemStack.EMPTY : last.equipment[i], living.getItemBySlot(SLOTS[i]))) {
				return true;
			}
		}

		return false;
	}

	static void writeEquipment(RegistryFriendlyByteBuf out, LivingEntity living, @Nullable Published last, Published into) {
		List<EquipmentSlot> slots = new ArrayList<>(SLOTS.length);

		for (int i = 0; i < SLOTS.length; i++) {
			ItemStack now = living.getItemBySlot(SLOTS[i]);

			if (last == null || !ItemStack.matches(last.equipment[i] == null ? ItemStack.EMPTY : last.equipment[i], now)) {
				slots.add(SLOTS[i]);
			}

			into.equipment[i] = now.copy();
		}

		out.writeVarInt(slots.size());

		for (EquipmentSlot slot : slots) {
			EquipmentSlot.STREAM_CODEC.encode(out, slot);
			ItemStack.OPTIONAL_STREAM_CODEC.encode(out, living.getItemBySlot(slot));
		}
	}

	static void writeData(RegistryFriendlyByteBuf out, @Nullable List<SynchedEntityData.DataValue<?>> values) {
		if (values == null) {
			out.writeVarInt(0);
			return;
		}

		out.writeVarInt(values.size());

		for (SynchedEntityData.DataValue<?> value : values) {
			value.write(out);
		}
	}

	static void writeEffect(RegistryFriendlyByteBuf out, Effect effect) {
		switch (effect) {
			case Swing(InteractionHand hand, SwingAnimation animation) -> {
				out.writeByte(1);
				InteractionHand.STREAM_CODEC.encode(out, hand);
				SwingAnimation.STREAM_CODEC.encode(out, animation);
			}
			case Event(byte id) -> {
				out.writeByte(2);
				out.writeByte(id);
			}
			case Animate(int action) -> {
				out.writeByte(3);
				out.writeVarInt(action);
			}
			case Damage(DamageSource source) -> {
				out.writeByte(4);
				writeSource(out, source);
			}
		}
	}

	static Effect readEffect(ServerLevel level, RegistryFriendlyByteBuf in) {
		return switch (in.readByte()) {
			case 1 -> new Swing(InteractionHand.STREAM_CODEC.decode(in), SwingAnimation.STREAM_CODEC.decode(in));
			case 2 -> new Event(in.readByte());
			case 3 -> new Animate(in.readVarInt());
			case 4 -> new Damage(readSource(level, in));
			default -> throw new DecoderException("unknown effect");
		};
	}

	static void writeSource(RegistryFriendlyByteBuf out, DamageSource source) {
		DamageType.STREAM_CODEC.encode(out, source.typeHolder());
		writeOptionalUuid(out, source.getEntity());
		writeOptionalUuid(out, source.getDirectEntity());
		Vec3 position = source.sourcePositionRaw();
		out.writeBoolean(position != null);

		if (position != null) {
			out.writeDouble(position.x);
			out.writeDouble(position.y);
			out.writeDouble(position.z);
		}
	}

	static DamageSource readSource(ServerLevel level, RegistryFriendlyByteBuf in) {
		Holder<DamageType> type = DamageType.STREAM_CODEC.decode(in);
		Entity causing = readOptionalEntity(level, in);
		Entity direct = readOptionalEntity(level, in);
		Vec3 position = in.readBoolean() ? new Vec3(in.readDouble(), in.readDouble(), in.readDouble()) : null;

		if (causing == null && direct == null && position != null) {
			return new DamageSource(type, position);
		}

		return new DamageSource(type, direct, causing);
	}

	static void writeOptionalUuid(RegistryFriendlyByteBuf out, @Nullable Entity entity) {
		out.writeBoolean(entity != null);

		if (entity != null) {
			out.writeUUID(entity.getUUID());
		}
	}

	static @Nullable Entity readOptionalEntity(ServerLevel level, RegistryFriendlyByteBuf in) {
		return in.readBoolean() ? level.getEntity(in.readUUID()) : null;
	}

	static List<SynchedEntityData.DataValue<?>> readData(RegistryFriendlyByteBuf in) {
		int count = in.readVarInt();
		List<SynchedEntityData.DataValue<?>> values = new ArrayList<>(Math.min(count, 64));

		for (int i = 0; i < count; i++) {
			values.add(SynchedEntityData.DataValue.read(in, in.readUnsignedByte()));
		}

		return values;
	}

	static List<Pair<EquipmentSlot, ItemStack>> readEquipment(RegistryFriendlyByteBuf in) {
		int count = in.readVarInt();
		List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>(Math.min(count, SLOTS.length));

		for (int i = 0; i < count; i++) {
			equipment.add(Pair.of(EquipmentSlot.STREAM_CODEC.decode(in), ItemStack.OPTIONAL_STREAM_CODEC.decode(in)));
		}

		return equipment;
	}	static final class Published {
		final UUID uuid;
		long chunk;
		int refreshedAt;
		Vec3 position;
		float yRot;
		float xRot;
		float headRot;
		Vec3 motion;
		boolean onGround;
		final ItemStack[] equipment = new ItemStack[SLOTS.length];
		@Nullable UUID vehicle;

		Published(UUID uuid) {
			this.uuid = uuid;
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/entities");

}
