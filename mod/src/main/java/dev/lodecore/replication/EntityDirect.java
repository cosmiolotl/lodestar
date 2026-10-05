package dev.lodecore.replication;

import dev.lodecore.Node;
import dev.lodecore.net.Wire;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.TagValueOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static dev.lodecore.replication.EntityWire.*;

final class EntityDirect {

	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/entities");
	static final int SPAWN = 6;
	static final int HURT = 7;
	private final Node node;
	private final EntityReplication replication;
	boolean spawning;
	private int spawnHops;
	EntityDirect(Node node, EntityReplication replication) { this.node = node; this.replication = replication; }
	public boolean interceptNewEntity(ServerLevel level, Entity entity) {
		if (!node.isConnected() || replication.applyingRemote || entity instanceof Player) {
			((KnownEntity) entity).lodecore$markKnown();
			return false;
		}

		int owner = node.ownerOf(level.dimension(), entity.chunkPosition().pack());

		// While a region has no owner yet, what appears in it stays here, and the owner's snapshot
		// settles it once there is one.
		if (owner == node.nodeId() || owner == Node.NO_NODE) {
			((KnownEntity) entity).lodecore$markKnown();
			return false;
		}

		// The owner runs its own spawners, and a passenger travels with its vehicle.
		if (spawning || entity.isPassenger() || spawnHops >= MAX_SPAWN_HOPS) {
			return true;
		}

		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());

		try {
			// With its passengers, which are dropped above when they are added in turn.
			if (!entity.save(output)) {
				return true;
			}

			CompoundTag tag = output.buildResult();
			RegistryFriendlyByteBuf out = direct(level, SPAWN);
			out.writeByte(spawnHops);
			out.writeNbt(tag);
			node.send(Wire.direct(owner, ByteBufUtil.getBytes(out)));
		} catch (RuntimeException e) {
			LOGGER.warn("Could not pass {} on to node #{}: {}", entity, owner, e.toString());
		}

		return true;
	}
	public boolean forwardHurt(LivingEntity target, ServerLevel level, DamageSource source, float amount) {
		return forwardHurt(target, level, source, amount, -1);
	}

	/**
	 * Damage to one of a mirror dragon's parts, before the dragon weighs it by the part and its
	 * phase: its authority does that, once.
	 *
	 * @return whether the damage was sent away, rather than to be done here
	 */
	public boolean forwardHurt(EnderDragon dragon, ServerLevel level, EnderDragonPart part, DamageSource source, float amount) {
		return forwardHurt(dragon, level, source, amount, List.of(dragon.getSubEntities()).indexOf(part));
	}

	private boolean forwardHurt(LivingEntity target, ServerLevel level, DamageSource source, float amount, int part) {
		if (!node.isConnected() || replication.applyingRemote || replication.isAuthority(level, target)) {
			return false;
		}

		int authority = replication.authorityOf(level, target);

		if (authority != Node.NO_NODE) {
			RegistryFriendlyByteBuf out = direct(level, HURT);
			out.writeUUID(target.getUUID());
			writeSource(out, source);
			out.writeFloat(amount);
			out.writeVarInt(part);
			node.send(Wire.direct(authority, ByteBufUtil.getBytes(out)));
		}

		return true;
	}

	private RegistryFriendlyByteBuf direct(ServerLevel level, int kind) {
		RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
		out.writeByte(kind);
		out.writeVarInt(node.dimensionId(level.dimension()));
		return out;
	}

	public void onDirect(int from, ByteBuffer payload) {
		try {
			RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload), node.server().registryAccess());
			int kind = in.readByte();
			ServerLevel level = node.level(in.readVarInt());

			if (level == null) {
				return;
			}

			switch (kind) {
				case SPAWN -> {
					int hops = in.readByte();
					CompoundTag tag = in.readNbt();

					if (tag != null) {
						spawn(level, tag, hops);
					}
				}
				case HURT -> {
					Entity target = level.getEntity(in.readUUID());
					DamageSource source = readSource(level, in);
					float amount = in.readFloat();
					int part = in.readVarInt();

					// Damage sent to a node that is no longer the authority is lost.
					if (target == null || !replication.isAuthority(level, target)) {
						break;
					}

					if (target instanceof EnderDragon dragon && part >= 0 && part < dragon.getSubEntities().length) {
						dragon.hurt(level, dragon.getSubEntities()[part], source, amount);
					} else {
						target.hurtServer(level, source, amount);
					}
				}
				default -> LOGGER.warn("Node #{} sent an unknown payload {}", from, kind);
			}
		} catch (IndexOutOfBoundsException | DecoderException | EncoderException | IllegalArgumentException e) {
			LOGGER.warn("Node #{} sent a malformed payload: {}", from, e.toString());
		}
	}

	private void spawn(ServerLevel level, CompoundTag tag, int hops) {
		Entity entity = EntityType.loadEntityRecursive(tag, level, new EntitySpawnRequest(EntitySpawnReason.LOAD, true), created -> created);

		if (entity == null || level.getEntity(entity.getUUID()) != null) {
			return;
		}

		spawnHops = hops + 1;

		try {
			level.addFreshEntityWithPassengers(entity);
		} finally {
			spawnHops = 0;
		}
	}
}
