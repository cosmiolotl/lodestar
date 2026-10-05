package dev.lodecore.replication;

import java.util.UUID;

import net.minecraft.world.entity.Entity;

/** Implemented by {@code ServerLevel} through {@code ServerLevelMixin}. */
public interface EntityLookup {
	/**
	 * Whether the level has an entity with this UUID at all. Unlike {@code getEntity}, this also
	 * sees entities in chunks that are loaded but not accessible, such as those at the edge of
	 * what is loaded.
	 */
	boolean lodecore$hasEntity(UUID uuid);

	/**
	 * Gives an entity of the level another id, which no other entity there may have. Use
	 * {@link EntityIds#reassign}, which checks.
	 */
	void lodecore$reassignId(Entity entity, int id);
}
