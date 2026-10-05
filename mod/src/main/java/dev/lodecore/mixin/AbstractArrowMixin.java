package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;

@Mixin(AbstractArrow.class)
abstract class AbstractArrowMixin {
	/**
	 * A player picks up a mirror only once their node has custody of it, so that only one player
	 * in the cluster gets it.
	 */
	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void lodecore$pickUpWithCustody(Player player, CallbackInfo ci) {
		if (Hooks.touch((Entity) (Object) this, player)) {
			ci.cancel();
		}
	}
}
