package dev.lodecore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.ServerTickRateManager;

@Mixin(ServerTickRateManager.class)
public interface ServerTickRateManagerAccessor {
	@Accessor("remainingSprintTicks")
	long lodecore$remainingSprintTicks();
}
