package dev.lodecore.mixin;

import java.util.function.BooleanSupplier;

import dev.lodecore.replication.Hooks;
import dev.lodecore.replication.WorldState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.storage.LevelData;

@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
	/**
	 * Before the server starts timing the tick, so that time spent waiting for the rest of the
	 * cluster does not count as this server's own tick time.
	 */
	@Inject(method = "tickServer", at = @At("HEAD"))
	private void lodecore$waitForTheCluster(BooleanSupplier haveTime, CallbackInfo ci) {
		Hooks.beforeServerTick((MinecraftServer) (Object) this);
	}

	// What the server keeps once for all its dimensions, which one node of the cluster keeps for all.

	@Inject(method = "setWeatherParameters", at = @At("TAIL"))
	private void lodecore$onWeatherSet(int clearTime, int rainTime, boolean raining, boolean thundering, CallbackInfo ci) {
		Hooks.onWorldStateChanged(WorldState.WEATHER);
	}

	@Inject(method = "onGameRuleChanged", at = @At("HEAD"))
	private void lodecore$onGameRuleChanged(GameRule<?> rule, Object value, CallbackInfo ci) {
		Hooks.onGameRuleChanged(rule);
	}

	@Inject(method = "setDifficulty", at = @At("TAIL"))
	private void lodecore$onDifficultySet(Difficulty difficulty, boolean ignoreLock, CallbackInfo ci) {
		Hooks.onWorldStateChanged(WorldState.DIFFICULTY);
	}

	@Inject(method = "setDifficultyLocked", at = @At("TAIL"))
	private void lodecore$onDifficultyLocked(boolean locked, CallbackInfo ci) {
		Hooks.onWorldStateChanged(WorldState.DIFFICULTY);
	}

	@Inject(method = "setRespawnData", at = @At("TAIL"))
	private void lodecore$onSpawnSet(LevelData.RespawnData respawnData, CallbackInfo ci) {
		Hooks.onWorldStateChanged(WorldState.SPAWN);
	}

	@Inject(method = "setDefaultGameType", at = @At("TAIL"))
	private void lodecore$onDefaultGameModeSet(GameType gameType, CallbackInfo ci) {
		Hooks.onWorldStateChanged(WorldState.DEFAULT_GAME_MODE);
	}
}
