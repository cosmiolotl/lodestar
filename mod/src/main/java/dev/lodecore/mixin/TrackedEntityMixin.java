package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import dev.lodecore.net.EncodedBroadcast;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.entity.Entity;

/** Mirrors have no client here, so they must not incur visibility and packet pairing work. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class TrackedEntityMixin {
	@Shadow @Final private Entity entity;
	@Shadow @Final private int range;
	@Shadow public abstract void removePlayer(ServerPlayer player);

	@ModifyVariable(method = {"sendToTrackingPlayers", "sendToTrackingPlayersFiltered"}, at = @At("HEAD"), argsOnly = true)
	private Packet<? super ClientGamePacketListener> lodecore$encodeBroadcastOnce(Packet<? super ClientGamePacketListener> packet) {
		return EncodedBroadcast.wrap(packet);
	}

	@Inject(method = "getEffectiveRange", at = @At("HEAD"), cancellable = true)
	private void lodecore$rangeWithoutPassengers(CallbackInfoReturnable<Integer> ci) {
		if (entity.getPassengers().isEmpty()) {
			ci.setReturnValue(entity.level().getServer().getScaledTrackingDistance(range));
		}
	}

	@Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
	private void lodecore$skipRemoteViewer(ServerPlayer player, CallbackInfo ci) {
		if (!Hooks.isRemotePlayer(player)) return;
		removePlayer(player);
		ci.cancel();
	}
}
