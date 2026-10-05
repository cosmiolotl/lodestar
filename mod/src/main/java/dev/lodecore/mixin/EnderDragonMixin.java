package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;

@Mixin(EnderDragon.class)
abstract class EnderDragonMixin {
	/**
	 * Damage to a mirror dragon goes to its authority with the part it hit, before the dragon
	 * weighs it by the part and its phase, so that the authority does that once, as it would for
	 * a hit of its own.
	 */
	@Inject(method = "hurt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/boss/enderdragon/EnderDragonPart;Lnet/minecraft/world/damagesource/DamageSource;F)Z",
			at = @At("HEAD"), cancellable = true)
	private void lodecore$hurtTheAuthority(ServerLevel level, EnderDragonPart part, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
		if (Hooks.forwardDragonHurt((EnderDragon) (Object) this, level, part, source, damage)) {
			cir.setReturnValue(true);
		}
	}
}
