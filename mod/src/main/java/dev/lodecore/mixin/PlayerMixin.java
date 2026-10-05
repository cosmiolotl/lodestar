package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

@Mixin(Player.class)
abstract class PlayerMixin {
	/** Using an entity another node is the authority for waits for custody of it. */
	@Inject(method = "interactOn", at = @At("HEAD"), cancellable = true)
	private void lodecore$interactWithCustody(Entity entity, InteractionHand hand, Vec3 location, CallbackInfoReturnable<InteractionResult> cir) {
		InteractionResult instead = Hooks.interact((Player) (Object) this, entity, hand, location);

		if (instead != null) {
			cir.setReturnValue(instead);
		}
	}

	/** So does hitting one that is not a mob or a player, whose damage goes to its authority. */
	@Inject(method = "attack", at = @At("HEAD"), cancellable = true)
	private void lodecore$attackWithCustody(Entity entity, CallbackInfo ci) {
		if (Hooks.attack((Player) (Object) this, entity)) {
			ci.cancel();
		}
	}
}
