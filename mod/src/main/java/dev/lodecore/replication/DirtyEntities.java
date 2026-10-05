package dev.lodecore.replication;

import dev.lodecore.Node;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

/** Lifecycle membership, coalesced mutations and periodic full refreshes; mirrors never enter the refresh wheel. */
final class DirtyEntities {
	private record Region(ServerLevel level, long position) { }
	private final EntityReplication replication;
	private final Map<Entity, Region> loaded = new IdentityHashMap<>();
	private final Map<Region, Set<Entity>> regions = new HashMap<>();
	private final Set<Entity> authoritative = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Map<Entity, Integer> deadlines = new IdentityHashMap<>();
	private final TreeMap<Integer, Set<Entity>> refreshes = new TreeMap<>();
	private Map<Entity, Integer> changed = new IdentityHashMap<>();

	DirtyEntities(EntityReplication replication) { this.replication = replication; }
	Collection<Entity> authoritative() { return Collections.unmodifiableSet(authoritative); }

	void add(Entity entity) {
		Region region = region(entity);
		Region previous = loaded.put(entity, region);
		if (previous != null && !previous.equals(region)) removeRegion(previous, entity);
		regions.computeIfAbsent(region, key -> Collections.newSetFromMap(new IdentityHashMap<>())).add(entity);
		changed(entity);
	}

	void changed(Entity entity) { changed(entity, EntityReplication.ALL); }

	void changed(Entity entity, int fields) {
		Region previous = loaded.get(entity);
		if (previous == null) return;
		ServerLevel level = (ServerLevel) entity.level();
		long position = Node.regionOf(entity.chunkPosition().pack());
		Region current = previous;
		if (previous.level() != level || previous.position() != position) {
			current = new Region(level, position);
			loaded.put(entity, current);
			removeRegion(previous, entity);
			regions.computeIfAbsent(current, key -> Collections.newSetFromMap(new IdentityHashMap<>())).add(entity);
		}
		if (replication.isAuthority(current.level(), entity) && !entity.isRemoved()) {
			if (authoritative.add(entity)) schedule(entity, current.level().getServer().getTickCount());
			changed.merge(entity, fields, (before, after) -> before | after);
		} else if (authoritative.remove(entity)) {
			unschedule(entity);
			changed.merge(entity, fields, (before, after) -> before | after); // Drop the publisher's old baseline after relinquishing authority.
		}
	}

	private Region region(Entity entity) {
		return new Region((ServerLevel) entity.level(), Node.regionOf(entity.chunkPosition().pack()));
	}

	void reconcile(ServerLevel level, UUID uuid) {
		Entity entity = level.getEntity(uuid);
		if (entity != null) changed(entity);
	}

	void regionChanged(ServerLevel level, long region) {
		for (Entity entity : Set.copyOf(regions.getOrDefault(new Region(level, region), Set.of()))) changed(entity);
	}

	Map<Entity, Integer> take(int now) {
		while (!refreshes.isEmpty() && refreshes.firstKey() <= now) {
			for (Entity entity : refreshes.pollFirstEntry().getValue()) {
				deadlines.remove(entity);
				changed.put(entity, EntityReplication.ALL);
				schedule(entity, now);
			}
		}
		Map<Entity, Integer> result = changed;
		changed = new IdentityHashMap<>();
		return result;
	}

	void refreshed(Entity entity, int now) {
		unschedule(entity);
		schedule(entity, now);
	}

	private void schedule(Entity entity, int now) {
		int deadline = now + (entity instanceof Player || entity instanceof ItemEntity ? 20 : 100);
		deadlines.put(entity, deadline);
		refreshes.computeIfAbsent(deadline, key -> Collections.newSetFromMap(new IdentityHashMap<>())).add(entity);
	}

	private void unschedule(Entity entity) {
		Integer deadline = deadlines.remove(entity);
		if (deadline == null) return;
		Set<Entity> bucket = refreshes.get(deadline);
		if (bucket != null && bucket.remove(entity) && bucket.isEmpty()) refreshes.remove(deadline);
	}

	private void removeRegion(Region region, Entity entity) {
		Set<Entity> entries = regions.get(region);
		if (entries != null && entries.remove(entity) && entries.isEmpty()) regions.remove(region);
	}

	void remove(Entity entity) {
		Region region = loaded.remove(entity);
		if (region != null) removeRegion(region, entity);
		authoritative.remove(entity);
		changed.remove(entity);
		unschedule(entity);
	}

	void clear() { loaded.clear(); regions.clear(); authoritative.clear(); changed.clear(); deadlines.clear(); refreshes.clear(); }
}
