package dev.lodecore.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.level.ChunkMap;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
	/** The values are {@code ChunkMap.TrackedEntity}, which {@link TrackedEntityAccessor} reaches into. */
	@Accessor("entityMap")
	Int2ObjectMap<?> lodecore$entityMap();
	@org.spongepowered.asm.mixin.gen.Invoker("getPlayerViewDistance")
	int lodecore$viewDistance(net.minecraft.server.level.ServerPlayer player);
}
