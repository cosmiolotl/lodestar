package dev.lodecore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.clock.ServerClockManager;

@Mixin(ServerClockManager.ServerClockInstance.class)
public interface ServerClockInstanceAccessor {
	@Accessor("totalTicks")
	void lodecore$setTotalTicks(long totalTicks);

	@Accessor("partialTick")
	void lodecore$setPartialTick(float partialTick);

	@Accessor("rate")
	void lodecore$setRate(float rate);

	@Accessor("paused")
	void lodecore$setPaused(boolean paused);
}
