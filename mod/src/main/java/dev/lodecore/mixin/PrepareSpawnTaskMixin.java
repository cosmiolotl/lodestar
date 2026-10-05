package dev.lodecore.mixin;

import java.util.function.Consumer;

import dev.lodecore.replication.Hooks;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;

/**
 * A joining player's data is loaded when this task starts. While this node is part of a cluster,
 * the task waits until lodestar has lent this node the player's data.
 */
@Mixin(PrepareSpawnTask.class)
abstract class PrepareSpawnTaskMixin {
	@Shadow
	@Final
	private NameAndId nameAndId;

	@Unique
	private @Nullable Consumer<Packet<?>> lodecore$waitingToStart;

	@Shadow
	public abstract void start(Consumer<Packet<?>> connection);

	@Inject(method = "start", at = @At("HEAD"), cancellable = true)
	private void lodecore$waitForPlayerData(Consumer<Packet<?>> connection, CallbackInfo ci) {
		if (!Hooks.isPlayerDataReady(this.nameAndId.id())) {
			this.lodecore$waitingToStart = connection;
			ci.cancel();
		}
	}

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void lodecore$startOncePlayerDataIsHere(CallbackInfoReturnable<Boolean> cir) {
		Consumer<Packet<?>> connection = this.lodecore$waitingToStart;

		if (connection == null) {
			return;
		}

		if (Hooks.isPlayerDataReady(this.nameAndId.id())) {
			this.lodecore$waitingToStart = null;
			this.start(connection);
		} else {
			cir.setReturnValue(false);
		}
	}
}
