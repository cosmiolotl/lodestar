package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import dev.lodecore.replication.WorldState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.ServerTickRateManager;

/** Notes {@code /tick} changing how the game runs: its rate, freezing, stepping and sprinting. */
@Mixin(ServerTickRateManager.class)
abstract class ServerTickRateManagerMixin {
	@Inject(method = "setFrozen", at = @At("TAIL"))
	private void lodecore$onFrozen(boolean frozen, CallbackInfo ci) {
		Hooks.onWorldStateChanged(WorldState.TICK);
	}

	@Inject(method = "setTickRate", at = @At("TAIL"))
	private void lodecore$onRate(float rate, CallbackInfo ci) {
		Hooks.onWorldStateChanged(WorldState.TICK);
	}

	@Inject(method = {"stepGameIfPaused", "requestGameToSprint"}, at = @At("RETURN"))
	private void lodecore$onStarted(int ticks, CallbackInfoReturnable<Boolean> cir) {
		Hooks.onWorldStateChanged(WorldState.TICK);
	}

	@Inject(method = {"stopStepping", "stopSprinting"}, at = @At("RETURN"))
	private void lodecore$onStopped(CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) {
			Hooks.onWorldStateChanged(WorldState.TICK);
		}
	}
}
