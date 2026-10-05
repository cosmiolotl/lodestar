package dev.lodecore;

import dev.lodecore.replication.EntityReplication;
import dev.lodecore.replication.TrackingIndex;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;

/** Mutation hooks are independent of replication suppression: mirror changes still need durable storage. */
public final class ChangeHooks {
	private static volatile Node node;
	private ChangeHooks() { }
	static void attach(Node value) { node = value; }
	private static Node current(ServerLevel level) {
		Node current = node;
		return current != null && current.server() == level.getServer() && current.server().isSameThread() ? current : null;
	}
	public static void entity(Entity entity) { changed(entity, EntityReplication.ALL); }
	public static void movement(Entity entity) { changed(entity, EntityReplication.MOVEMENT); }
	public static void metadata(Entity entity) { changed(entity, EntityReplication.METADATA); }
	public static void equipment(Entity entity) { changed(entity, EntityReplication.EQUIPMENT); }
	private static void changed(Entity entity, int fields) {
		if (!(entity.level() instanceof ServerLevel level)) return;
		Node current = current(level);
		if (current == null) return;
		current.entities().changed(entity, fields);
		current.persistence.entityChanged(entity);
	}

	public static void position(Entity entity) {
		movement(entity);
		if (!(entity.level() instanceof ServerLevel level) || current(level) == null) return;
		((TrackingIndex) level.getChunkSource().chunkMap).lodecore$moved(entity);
	}

	public static void entityTick(Entity entity) {
		if (!(entity.level() instanceof ServerLevel level)) return;
		Node current = current(level);
		if (current != null) current.persistence.entityChanged(entity);
	}
	public static void chunk(LevelChunk chunk) {
		if (!(chunk.getLevel() instanceof ServerLevel level)) return;
		Node current = node;
		if (current != null && current.server() == level.getServer()) current.persistence.changed(level, chunk.getPos().pack());
	}
}
