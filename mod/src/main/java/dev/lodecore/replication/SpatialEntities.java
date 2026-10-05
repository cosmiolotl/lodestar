package dev.lodecore.replication;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/** Chunk columns bound visibility candidates without depending on entity bounding boxes. */
final class SpatialEntities<T extends Entity> {
	private final Map<Long, Set<T>> cells = new HashMap<>();
	private final Map<T, Long> positions = new IdentityHashMap<>();

	void put(T entity) {
		long chunk = entity.chunkPosition().pack();
		Long previous = positions.put(entity, chunk);
		if (previous != null && previous == chunk) return;
		if (previous != null) removeFrom(previous, entity);
		cells.computeIfAbsent(chunk, key -> Collections.newSetFromMap(new IdentityHashMap<>())).add(entity);
	}

	void remove(T entity) {
		Long previous = positions.remove(entity);
		if (previous != null) removeFrom(previous, entity);
	}

	private void removeFrom(long chunk, T entity) {
		Set<T> entries = cells.get(chunk);
		if (entries != null && entries.remove(entity) && entries.isEmpty()) cells.remove(chunk);
	}

	void nearby(ChunkPos center, int radius, Consumer<T> action) {
		long diameter = 2L * radius + 1;
		if (cells.size() < diameter * diameter) {
			for (var cell : cells.entrySet()) {
				long chunk = cell.getKey();
				if (Math.abs((long) ChunkPos.getX(chunk) - center.x()) <= radius
						&& Math.abs((long) ChunkPos.getZ(chunk) - center.z()) <= radius) cell.getValue().forEach(action);
			}
			return;
		}
		for (int x = center.x() - radius; x <= center.x() + radius; x++) {
			for (int z = center.z() - radius; z <= center.z() + radius; z++) {
				Set<T> entries = cells.get(ChunkPos.pack(x, z));
				if (entries != null) entries.forEach(action);
			}
		}
	}
}
