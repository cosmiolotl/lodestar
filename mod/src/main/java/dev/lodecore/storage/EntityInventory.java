package dev.lodecore.storage;

import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/** Associates a standalone container with the entity whose durable state includes it. */
public interface EntityInventory {
	void lodecore$owner(@Nullable Entity entity);
}
