package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import dev.lodecore.shared.SharedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.bossevents.CustomBossEvent;

@Mixin(CustomBossEvent.class)
abstract class CustomBossEventMixin {
	/** Called on every change to the bar that is saved: its options and its players. */
	@Inject(method = "setDirty", at = @At("HEAD"))
	private void lodecore$onChanged(CallbackInfo ci) {
		SharedData shared = Hooks.shared();

		if (shared != null) {
			shared.bossBars().onChanged((CustomBossEvent) (Object) this);
		}
	}
}
