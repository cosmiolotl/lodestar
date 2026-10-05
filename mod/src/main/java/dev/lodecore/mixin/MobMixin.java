package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.Mob;

@Mixin(Mob.class)
abstract class MobMixin {
	/** Only a mob's authority decides when it despawns: a mirror's nearest player is not its own. */
	@Inject(method = "checkDespawn", at = @At("HEAD"), cancellable = true)
	private void lodecore$mirrorsDoNotDespawn(CallbackInfo ci) {
		if (!Hooks.mayTick((Mob) (Object) this)) {
			ci.cancel();
		}
	}
}
