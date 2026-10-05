package dev.lodecore.mixin;

import java.util.function.Consumer;

import dev.lodecore.replication.Hooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.Holder;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;

/** Notes a clock set, moved, paused or sped up, as opposed to one that merely ticked. */
@Mixin(ServerClockManager.class)
abstract class ServerClockManagerMixin {
	@Inject(method = "modifyClock", at = @At("TAIL"))
	private void lodecore$onClockChanged(Holder<WorldClock> clock, Consumer<?> action, CallbackInfo ci) {
		Hooks.onClockChanged(clock);
	}
}
