package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;

@Mixin(ItemEntity.class)
abstract class ItemEntityMixin {
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

	/** Nothing here, mob or hopper, picks up a mirror either: it is not this node's to give. */
	@Inject(method = "hasPickUpDelay", at = @At("RETURN"), cancellable = true)
	private void lodecore$mirrorsCannotBePickedUp(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && Hooks.isUntouchable((ItemEntity) (Object) this)) {
			cir.setReturnValue(true);
		}
	}

	/** Items only merge with items this node is the authority for. */
	@Inject(method = "isMergable", at = @At("RETURN"), cancellable = true)
	private void lodecore$mergeOnlyAuthoritativeItems(CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && Hooks.isUntouchable((ItemEntity) (Object) this)) {
			cir.setReturnValue(false);
		}
	}
}
