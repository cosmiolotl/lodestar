package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import dev.lodecore.replication.KnownEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.Entity;

@Mixin(Entity.class)
abstract class EntityMixin implements KnownEntity {
	@Unique
	private boolean lodecore$known;

	@Override
	public boolean lodecore$known() {
		return lodecore$known;
	}

	@Override
	public void lodecore$markKnown() {
		lodecore$known = true;
	}

	/** Only an entity's authority decides when it despawns. */
	@Inject(method = "checkDespawn", at = @At("HEAD"), cancellable = true)
	private void lodecore$mirrorsDoNotDespawn(CallbackInfo ci) {
		if (!Hooks.mayTick((Entity) (Object) this)) {
			ci.cancel();
		}
	}
}
