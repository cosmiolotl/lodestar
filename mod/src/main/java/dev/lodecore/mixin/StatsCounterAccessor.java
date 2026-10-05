package dev.lodecore.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.stats.Stat;
import net.minecraft.stats.StatsCounter;

@Mixin(StatsCounter.class)
public interface StatsCounterAccessor {
	@Accessor("stats")
	Object2IntMap<Stat<?>> lodecore$stats();
}
