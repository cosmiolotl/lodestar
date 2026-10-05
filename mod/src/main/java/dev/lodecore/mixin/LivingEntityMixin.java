package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
	/**
	 * Damage to a mirror is done to its authority's copy. It counts as having landed, so that the
	 * attacker's side of a hit (its weapon wearing down, its attack cooling down) still happens.
	 */
	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void lodecore$hurtTheAuthority(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
		if (Hooks.forwardHurt((LivingEntity) (Object) this, level, source, damage)) {
			cir.setReturnValue(true);
		}
	}
}
