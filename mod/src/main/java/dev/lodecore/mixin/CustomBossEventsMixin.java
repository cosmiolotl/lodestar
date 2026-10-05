package dev.lodecore.mixin;

import java.util.Map;

import dev.lodecore.replication.Hooks;
import dev.lodecore.shared.SharedData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.bossevents.CustomBossEvent;
import net.minecraft.server.bossevents.CustomBossEvents;
import net.minecraft.util.RandomSource;

@Mixin(CustomBossEvents.class)
abstract class CustomBossEventsMixin {
	@Shadow
	@Final
	private Map<Identifier, CustomBossEvent> events;

	@Inject(method = "create", at = @At("RETURN"))
	private void lodecore$onCreated(RandomSource random, Identifier id, Component name, CallbackInfoReturnable<CustomBossEvent> cir) {
		SharedData shared = Hooks.shared();

		if (shared != null) {
			shared.bossBars().onChanged(cir.getReturnValue());
		}
	}

	@Inject(method = "remove", at = @At("HEAD"))
	private void lodecore$onRemoved(CustomBossEvent bar, CallbackInfo ci) {
		SharedData shared = Hooks.shared();

		if (shared != null && this.events.get(bar.customId()) == bar) {
			shared.bossBars().onRemoved(bar);
		}
	}
}
