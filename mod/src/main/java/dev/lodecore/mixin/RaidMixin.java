package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import dev.lodecore.replication.RaidSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.advancements.triggers.PlayerTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.raid.Raid;

/** The heroes of a won raid on other nodes are rewarded there, and its horn is heard there too. */
@Mixin(Raid.class)
abstract class RaidMixin {
	@Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;)Z"))
	private boolean lodecore$rewardHero(LivingEntity hero, MobEffectInstance effect) {
		RaidSync raids = Hooks.raids();
		return raids == null ? hero.addEffect(effect) : raids.reward(hero, effect);
	}

	@Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;awardStat(Lnet/minecraft/resources/Identifier;)V"))
	private void lodecore$countWin(ServerPlayer hero, Identifier stat) {
		RaidSync raids = Hooks.raids();

		if (raids == null || !raids.isRewardedElsewhere(hero)) {
			hero.awardStat(stat);
		}
	}

	@Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/advancements/triggers/PlayerTrigger;trigger(Lnet/minecraft/server/level/ServerPlayer;)V"))
	private void lodecore$triggerWin(PlayerTrigger trigger, ServerPlayer hero) {
		RaidSync raids = Hooks.raids();

		if (raids == null || !raids.isRewardedElsewhere(hero)) {
			trigger.trigger(hero);
		}
	}

	@Inject(method = "playSound", at = @At("HEAD"))
	private void lodecore$onHorn(ServerLevel level, BlockPos origin, CallbackInfo ci) {
		RaidSync raids = Hooks.raids();

		if (raids != null) {
			raids.onHorn((Raid) (Object) this, origin);
		}
	}
}
