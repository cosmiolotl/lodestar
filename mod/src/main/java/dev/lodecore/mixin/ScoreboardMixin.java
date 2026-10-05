package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.Scoreboard;

@Mixin(Scoreboard.class)
abstract class ScoreboardMixin {
	/**
	 * A dead entity's scores go with it, but a mirror going is not its entity dying: it moved out
	 * of this node's sight, or its authority will say if it died.
	 */
	@Inject(method = "entityRemoved", at = @At("HEAD"), cancellable = true)
	private void lodecore$keepScoresOfMirrors(Entity entity, CallbackInfo ci) {
		if (Hooks.isMirror(entity)) {
			ci.cancel();
		}
	}
}
