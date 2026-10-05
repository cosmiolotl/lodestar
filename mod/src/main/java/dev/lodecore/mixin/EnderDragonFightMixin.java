package dev.lodecore.mixin;

import dev.lodecore.replication.DragonFightSync;
import dev.lodecore.replication.Hooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.dimension.end.EnderDragonFight;

/** The fight is run by the node that simulates its origin; see {@link DragonFightSync}. */
@Mixin(EnderDragonFight.class)
abstract class EnderDragonFightMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void lodecore$tickCopy(CallbackInfo ci) {
		DragonFightSync fights = Hooks.dragonFights();
		EnderDragonFight fight = (EnderDragonFight) (Object) this;

		if (fights != null && !fights.runsHere(fight)) {
			fights.tickCopy(fight);
			ci.cancel();
		}
	}

	@Inject(method = "setDragonKilled", at = @At("HEAD"), cancellable = true)
	private void lodecore$forwardKill(EnderDragon dragon, CallbackInfo ci) {
		DragonFightSync fights = Hooks.dragonFights();

		if (fights != null && fights.forwardKill((EnderDragonFight) (Object) this, dragon)) {
			ci.cancel();
		}
	}

	@Inject(method = "tryRespawn", at = @At("HEAD"), cancellable = true)
	private void lodecore$forwardRespawn(CallbackInfo ci) {
		DragonFightSync fights = Hooks.dragonFights();

		if (fights != null && fights.forwardRespawn((EnderDragonFight) (Object) this)) {
			ci.cancel();
		}
	}

	/** The node that runs the fight counts the crystals itself. */
	@Inject(method = "onCrystalDestroyed", at = @At("HEAD"), cancellable = true)
	private void lodecore$leaveCrystals(EndCrystal crystal, DamageSource source, CallbackInfo ci) {
		DragonFightSync fights = Hooks.dragonFights();

		if (fights != null && !fights.runsHere((EnderDragonFight) (Object) this)) {
			ci.cancel();
		}
	}
}
