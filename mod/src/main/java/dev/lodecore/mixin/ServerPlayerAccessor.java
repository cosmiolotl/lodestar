package dev.lodecore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.level.ServerPlayer;

@Mixin(ServerPlayer.class)
public interface ServerPlayerAccessor {
	@Accessor("containerCounter")
	int lodecore$containerCounter();

	@Accessor("containerCounter")
	void lodecore$setContainerCounter(int counter);
}
