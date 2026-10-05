package dev.lodecore.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;

@Mixin(LevelTicks.class)
public interface LevelTicksAccessor<T> {
	@Accessor("allContainers")
	Long2ObjectMap<LevelChunkTicks<T>> lodecore$allContainers();
}
