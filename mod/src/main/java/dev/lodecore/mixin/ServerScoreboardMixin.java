package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import dev.lodecore.shared.SharedData;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.ScoreHolder;

/** Notes every change to the scoreboard, where the game tells the players of it. */
@Mixin(ServerScoreboard.class)
abstract class ServerScoreboardMixin {
	@Inject(method = "onScoreChanged", at = @At("TAIL"))
	private void lodecore$onScoreChanged(ScoreHolder owner, Objective objective, Score score, CallbackInfo ci) {
		SharedData shared = Hooks.shared();

		if (shared != null) {
			shared.scoreboard().onScoreChanged(owner, objective);
		}
	}

	@Inject(method = {"onScoreLockChanged", "onPlayerScoreRemoved"}, at = @At("TAIL"))
	private void lodecore$onScoreLockedOrRemoved(ScoreHolder owner, Objective objective, CallbackInfo ci) {
		SharedData shared = Hooks.shared();

		if (shared != null) {
			shared.scoreboard().onScoreChanged(owner, objective);
		}
	}

	@Inject(method = "onPlayerRemoved", at = @At("TAIL"))
	private void lodecore$onHolderRemoved(ScoreHolder holder, CallbackInfo ci) {
		SharedData shared = Hooks.shared();

		if (shared != null) {
			shared.scoreboard().onHolderRemoved(holder);
		}
	}

	@Inject(method = "setDisplayObjective", at = @At("TAIL"))
	private void lodecore$onDisplaySet(DisplaySlot slot, @Nullable Objective objective, CallbackInfo ci) {
		SharedData shared = Hooks.shared();

		if (shared != null) {
			shared.scoreboard().onDisplayChanged(slot);
		}
	}

	@Inject(method = "addPlayerToTeam", at = @At("RETURN"))
	private void lodecore$onJoinedTeam(String player, PlayerTeam team, CallbackInfoReturnable<Boolean> cir) {
		SharedData shared = Hooks.shared();

		if (shared != null && cir.getReturnValueZ()) {
			shared.scoreboard().onMemberChanged(player);
		}
	}

	@Inject(method = "removePlayerFromTeam", at = @At("TAIL"))
	private void lodecore$onLeftTeam(String player, PlayerTeam team, CallbackInfo ci) {
		SharedData shared = Hooks.shared();

		if (shared != null) {
			shared.scoreboard().onMemberChanged(player);
		}
	}

	@Inject(method = {"onObjectiveAdded", "onObjectiveChanged"}, at = @At("TAIL"))
	private void lodecore$onObjectiveChanged(Objective objective, CallbackInfo ci) {
		SharedData shared = Hooks.shared();

		if (shared != null) {
			shared.scoreboard().onObjectiveChanged(objective);
		}
	}

	@Inject(method = "onObjectiveRemoved", at = @At("TAIL"))
	private void lodecore$onObjectiveRemoved(Objective objective, CallbackInfo ci) {
		SharedData shared = Hooks.shared();

		if (shared != null) {
			shared.scoreboard().onObjectiveRemoved(objective);
		}
	}

	@Inject(method = {"onTeamAdded", "onTeamChanged"}, at = @At("TAIL"))
	private void lodecore$onTeamChanged(PlayerTeam team, CallbackInfo ci) {
		SharedData shared = Hooks.shared();

		if (shared != null) {
			shared.scoreboard().onTeamChanged(team);
		}
	}

	@Inject(method = "onTeamRemoved", at = @At("TAIL"))
	private void lodecore$onTeamRemoved(PlayerTeam team, CallbackInfo ci) {
		SharedData shared = Hooks.shared();

		if (shared != null) {
			shared.scoreboard().onTeamRemoved(team);
		}
	}
}
