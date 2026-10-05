package dev.lodecore.mixin;

import java.util.function.UnaryOperator;

import dev.lodecore.replication.Hooks;
import dev.lodecore.shared.SharedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.resources.Identifier;
import net.minecraft.world.Stopwatch;
import net.minecraft.world.Stopwatches;

@Mixin(Stopwatches.class)
abstract class StopwatchesMixin {
	@Inject(method = "add", at = @At("RETURN"))
	private void lodecore$onAdded(Identifier id, Stopwatch stopwatch, CallbackInfoReturnable<Boolean> cir) {
		onChanged(id, cir);
	}

	@Inject(method = "update", at = @At("RETURN"))
	private void lodecore$onUpdated(Identifier id, UnaryOperator<Stopwatch> update, CallbackInfoReturnable<Boolean> cir) {
		onChanged(id, cir);
	}

	@Inject(method = "remove", at = @At("RETURN"))
	private void lodecore$onRemoved(Identifier id, CallbackInfoReturnable<Boolean> cir) {
		onChanged(id, cir);
	}

	private static void onChanged(Identifier id, CallbackInfoReturnable<Boolean> cir) {
		SharedData shared = Hooks.shared();

		if (shared != null && cir.getReturnValueZ()) {
			shared.serverData().onStopwatchChanged(id);
		}
	}
}
