package dev.lodecore.mixin;

import dev.lodecore.handoff.BossBars;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerBossEvent;

@Mixin(ServerBossEvent.class)
abstract class ServerBossEventMixin {
	@Inject(method = "<init>", at = @At("TAIL"))
	private void lodecore$register(CallbackInfo ci) {
		BossBars.register((ServerBossEvent) (Object) this);
	}
}
