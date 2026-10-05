package dev.lodecore.replication;

import dev.lodecore.mixin.ChunkMapAccessor;
import dev.lodecore.mixin.TrackedEntityAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/** One visibility pass before vanilla sends changes, with old viewers included for unpairing. */
public final class TrackingUpdates {
	private record View(ChunkPos chunk, int distance) { }
	private final SpatialEntities<Entity> spatial = new SpatialEntities<>();
	private final Set<Entity> changed = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Map<ServerPlayer, View> previousViews = new IdentityHashMap<>();

	public void added(Entity entity) { spatial.put(entity); changed.add(entity); }
	public void removed(Entity entity) { spatial.remove(entity); changed.remove(entity); previousViews.remove(entity); }
	public void moved(Entity entity) { changed.add(entity); }

	public void flush(ServerLevel level, Int2ObjectMap<?> entities) {
		Set<Entity> moved = new HashSet<>(changed);
		changed.clear();
		for (Entity entity : moved) {
			if (entities.containsKey(entity.getId())) spatial.put(entity);
		}
		SpatialEntities<ServerPlayer> viewers = new SpatialEntities<>();
		Map<ServerPlayer, View> currentViews = new IdentityHashMap<>();
		ChunkMapAccessor chunks = (ChunkMapAccessor) level.getChunkSource().chunkMap;
		int maxDistance = 0;
		for (ServerPlayer player : level.players()) {
			if (Hooks.isRemotePlayer(player)) continue;
			int distance = chunks.lodecore$viewDistance(player);
			currentViews.put(player, new View(player.chunkPosition(), distance));
			viewers.put(player);
			maxDistance = Math.max(maxDistance, distance);
		}
		Set<ServerPlayer> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Entity entity : moved) {
			Object entry = entities.get(entity.getId());
			if (entry == null) continue;
			TrackedEntityAccessor tracked = (TrackedEntityAccessor) entry;
			candidates.clear();
			int radius = Math.min(maxDistance, (tracked.lodecore$effectiveRange() + 15) / 16) + 1;
			viewers.nearby(entity.chunkPosition(), radius, candidates::add);
			for (var connection : tracked.lodecore$seenBy()) candidates.add(connection.getPlayer());
			for (ServerPlayer player : candidates) tracked.lodecore$updatePlayer(player);
		}
		Set<Entity> nearby = Collections.newSetFromMap(new IdentityHashMap<>());
		for (var entry : currentViews.entrySet()) {
			ServerPlayer player = entry.getKey();
			View current = entry.getValue();
			View previous = previousViews.get(player);
			if (!moved.contains(player) && current.equals(previous)) continue;
			nearby.clear();
			spatial.nearby(current.chunk(), current.distance() + 1, nearby::add);
			if (previous != null) spatial.nearby(previous.chunk(), previous.distance() + 1, nearby::add);
			for (Entity entity : nearby) {
				// Moving entities already checked every candidate viewer above.
				if (moved.contains(entity)) continue;
				Object tracked = entities.get(entity.getId());
				if (tracked != null) ((TrackedEntityAccessor) tracked).lodecore$updatePlayer(player);
			}
		}
		previousViews.clear();
		previousViews.putAll(currentViews);
	}
}
