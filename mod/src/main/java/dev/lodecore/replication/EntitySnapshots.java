package dev.lodecore.replication;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import static dev.lodecore.replication.EntityWire.*;

final class EntitySnapshots {

	private final EntityReplication replication;
	private final EntityMirrors mirrors;
	EntitySnapshots(EntityReplication replication, EntityMirrors mirrors) { this.replication = replication; this.mirrors = mirrors; }
	public void writeSnapshot(ServerLevel level, ChunkPos chunkPos, RegistryFriendlyByteBuf out) {
		List<Pair<Entity, CompoundTag>> entities = new ArrayList<>();

		for (Entity entity : entitiesIn(level, chunkPos)) {
			CompoundTag tag = save(level, entity);

			if (tag != null) {
				entities.add(Pair.of(entity, tag));
			}
		}

		out.writeVarInt(entities.size());

		for (Pair<Entity, CompoundTag> entity : entities) {
			out.writeUUID(entity.getFirst().getUUID());
			out.writeVarInt(entity.getFirst().getId());
			out.writeNbt(entity.getSecond());
		}
	}

	public void applySnapshot(ServerLevel level, ChunkPos chunkPos, RegistryFriendlyByteBuf in) {
		int count = in.readVarInt();
		Set<UUID> present = new HashSet<>();
		replication.applyingRemote = true;

		try {
			for (int i = 0; i < count; i++) {
				UUID uuid = in.readUUID();
				int id = in.readVarInt();
				CompoundTag tag = in.readNbt();
				present.add(uuid);

				if (tag != null) {
					mirrors.applyWhole(level, uuid, tag, id);
				}
			}

			for (Entity entity : entitiesIn(level, chunkPos)) {
				if (!present.contains(entity.getUUID()) && !replication.isAuthority(level, entity)) {
					entity.discard();
				}
			}
		} finally {
			replication.applyingRemote = false;
		}
	}

	private static List<Entity> entitiesIn(ServerLevel level, ChunkPos chunkPos) {
		AABB bounds = new AABB(
				chunkPos.getMinBlockX(), level.getMinY(), chunkPos.getMinBlockZ(),
				chunkPos.getMaxBlockX() + 1, level.getMaxY() + 1, chunkPos.getMaxBlockZ() + 1);
		// A dragon's parts are found by a search of an area too, but are not entities of their own:
		// they come and go with their dragon.
		return level.getEntities(
				(Entity) null, bounds, entity -> !(entity instanceof Player) && !(entity instanceof EnderDragonPart) && entity.chunkPosition().equals(chunkPos));
	}
}
