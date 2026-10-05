package dev.lodecore.replication;

import com.mojang.authlib.GameProfile;
import dev.lodecore.Node;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import static dev.lodecore.replication.EntityWire.*;

/** Coordinates authoritative dirty queues, mirror application, and ownership transitions on the game thread. */
public final class EntityReplication {

	public static final int ENTITIES = 5;
	public static final int MOVEMENT = 1;
	public static final int METADATA = 2;
	public static final int EQUIPMENT = 4;
	public static final int ALL = MOVEMENT | METADATA | EQUIPMENT;
	private final Node node;
	private final RemotePlayers remotePlayers;
	boolean applyingRemote;
	final List<Entity> staleEntities = new ArrayList<>();
	final DirtyEntities dirty;
	private final EntityPublisher publisher;
	private final EntityMirrors mirrors;
	private final EntitySnapshots snapshots;
	private final EntityDirect direct;

	public EntityReplication(Node node) {
		this.node = node;
		remotePlayers = new RemotePlayers(node);
		dirty = new DirtyEntities(this);
		publisher = new EntityPublisher(node, this);
		mirrors = new EntityMirrors(node, this, remotePlayers);
		snapshots = new EntitySnapshots(this, mirrors);
		direct = new EntityDirect(node, this);
	}
	public static boolean handlesDirect(int kind) { return kind == EntityDirect.SPAWN || kind == EntityDirect.HURT; }
	public void flush() {
		for (Entity entity : List.copyOf(staleEntities)) if (!entity.isRemoved()) entity.discard();
		staleEntities.clear();
		remotePlayers.flush();
		publisher.flush();
	}
	public void authorityChanged(ServerLevel level, ByteBuffer object) {
		try {
			ObjectKey key = ObjectKey.fromBytes(object);
			if (key.isEntity()) dirty.reconcile(level, key.entity());
		} catch (IllegalArgumentException ignored) { }
	}
	public void changed(Entity entity) { dirty.changed(entity); }
	public void changed(Entity entity, int fields) { dirty.changed(entity, fields); }
	public Collection<Entity> authoritative() { return dirty.authoritative(); }
	public void onEntityUnload(ServerLevel level, Entity entity) {
		dirty.remove(entity);
		publisher.onEntityUnload(level, entity);
		if (entity instanceof ServerPlayer player) remotePlayers.onUnloaded(player);
	}
	public void onEntityPacket(ServerLevel level, Entity entity, Packet<?> packet) { publisher.onEntityPacket(level, entity, packet); }
	public void onRelay(int from, ServerLevel level, ChunkPos chunk, ByteBuffer payload) { mirrors.onRelay(from, level, chunk, payload); }
	public void onDirect(int from, ByteBuffer payload) { direct.onDirect(from, payload); }
	void adopt(ServerLevel level, UUID uuid, CompoundTag tag) { mirrors.adopt(level, uuid, tag); dirty.reconcile(level, uuid); }
	void forget(ServerLevel level, UUID uuid) { mirrors.forget(level, uuid); }
	@Nullable CompoundTag saveWhole(ServerLevel level, Entity entity) { return EntityWire.save(level, entity); }
	public void writeSnapshot(ServerLevel level, ChunkPos chunk, RegistryFriendlyByteBuf out) { snapshots.writeSnapshot(level, chunk, out); }
	public void applySnapshot(ServerLevel level, ChunkPos chunk, RegistryFriendlyByteBuf in) { snapshots.applySnapshot(level, chunk, in); }
	public boolean interceptNewEntity(ServerLevel level, Entity entity) { return direct.interceptNewEntity(level, entity); }
	public boolean forwardHurt(LivingEntity target, ServerLevel level, DamageSource source, float amount) { return direct.forwardHurt(target, level, source, amount); }
	public boolean forwardHurt(EnderDragon dragon, ServerLevel level, EnderDragonPart part, DamageSource source, float amount) { return direct.forwardHurt(dragon, level, part, source, amount); }
	public void setSpawning(boolean spawning) { direct.spawning = spawning; }
	public boolean isAuthority(ServerLevel level, Entity entity) {
		if (!node.isConnected()) {
			return true;
		}

		if (entity instanceof ServerPlayer player) {
			return !remotePlayers.isMirror(player);
		}

		int holder = node.custody().holderOf(level, entity);
		return holder != Node.NO_NODE ? holder == node.nodeId() : node.owns(level, entity.chunkPosition().pack());
	}

	int authorityOf(ServerLevel level, Entity entity) {
		if (entity instanceof Player) {
			return node.homeOf(entity.getUUID());
		}

		int holder = node.custody().holderOf(level, entity);
		return holder != Node.NO_NODE ? holder : node.ownerOf(level.dimension(), entity.chunkPosition().pack());
	}

	int recordAuthority(ServerLevel level, UUID uuid, ChunkPos chunkPos) {
		int holder = node.custody().holderOf(level, uuid);
		return holder != Node.NO_NODE ? holder : node.ownerOf(level.dimension(), chunkPos.pack());
	}

	public boolean isRemotePlayer(ServerPlayer player) {
		return remotePlayers.isMirror(player);
	}

	public boolean isRemotePlayerElsewhere(ServerLevel level, ServerPlayer player) {
		return remotePlayers.isMirror(player) && !node.owns(level, player.chunkPosition().pack());
	}

	public void onEntityLoad(ServerLevel level, Entity entity) {
		dirty.add(entity);
		KnownEntity known = (KnownEntity) entity;

		if (entity instanceof Player || known.lodecore$known()) {
			return;
		}

		int owner = node.ownerOf(level.dimension(), entity.chunkPosition().pack());

		if (!node.isConnected() || applyingRemote || owner == node.nodeId() || owner == Node.NO_NODE) {
			known.lodecore$markKnown();
		} else {
			// Not from within the game's own entity bookkeeping, which is what is calling.
			staleEntities.add(entity);
		}
	}

	public void onRegionOwner(ServerLevel level, long region) {
		remotePlayers.onRegionOwner(level, region);
		dirty.regionChanged(level, region);
	}

	public void onPlayerLeft(UUID uuid) {
		remotePlayers.remove(uuid);
	}

	public Collection<ServerPlayer> remotePlayerMirrors() {
		return remotePlayers.all();
	}

	public @Nullable ServerPlayer remotePlayerMirror(UUID uuid) {
		return remotePlayers.get(uuid);
	}

	public ServerPlayer mirrorRemotePlayer(ServerLevel level, GameProfile profile, Vec3 position, int id) {
		return remotePlayers.create(level, profile, new Move(position, 0, 0, 0, Vec3.ZERO, false), id);
	}

	public void dropMirror(ServerPlayer mirror) {
		remotePlayers.remove(mirror);
	}

	public void promote(ServerPlayer mirror) {
		remotePlayers.forget(mirror);
		dirty.changed(mirror);
	}

	public void demote(ServerPlayer player) {
		publisher.forget(player);
		remotePlayers.adopt(player);
		dirty.changed(player);
	}
	public void introduceRemotePlayers(ServerPlayer player) { remotePlayers.introduceTo(player); }
	public void onDisconnected() {
		remotePlayers.removeAll(); publisher.clear(); staleEntities.clear(); dirty.clear();
	}

}
