package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import dev.lodecore.shared.SharedData;
import dev.lodecore.shared.UserListSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.dedicated.DedicatedServer;

@Mixin(DedicatedServer.class)
abstract class DedicatedServerMixin {
	/**
	 * A node never pauses when its last player leaves. A paused server skips the work of a
	 * tick, so it would stop answering lodestar's clock and be dropped from the cluster, and an
	 * owner would stop simulating its regions for everyone else.
	 */
	@Inject(method = "pauseWhenEmptySeconds", at = @At("HEAD"), cancellable = true)
	private void lodecore$neverPause(CallbackInfoReturnable<Integer> cir) {
		cir.setReturnValue(0);
	}

	@Inject(method = "setUsingWhitelist", at = @At("TAIL"))
	private void lodecore$onWhitelistSwitched(boolean on, CallbackInfo ci) {
		SharedData shared = Hooks.shared();

		if (shared != null) {
			shared.userLists().onFlagChanged(UserListSync.WHITELIST_ON);
		}
	}

	@Inject(method = "setEnforceWhitelist", at = @At("TAIL"))
	private void lodecore$onWhitelistEnforced(boolean on, CallbackInfo ci) {
		SharedData shared = Hooks.shared();

		if (shared != null) {
			shared.userLists().onFlagChanged(UserListSync.WHITELIST_ENFORCED);
		}
	}
}
