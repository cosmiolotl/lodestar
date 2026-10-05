package dev.lodecore.mixin;

import java.util.Set;
import java.util.HashSet;
import dev.lodecore.replication.Hooks;
import dev.lodecore.replication.WaypointUpdates;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remote players remain waypoint transmitters, but only local clients need waypoint connections. */
@Mixin(ServerWaypointManager.class)
abstract class ServerWaypointManagerMixin implements WaypointUpdates {
	@Shadow @Final private Set<ServerPlayer> players;
	@Shadow public abstract void trackWaypoint(WaypointTransmitter waypoint);
	@Shadow public abstract void updateWaypoint(WaypointTransmitter waypoint);
	@Shadow public abstract void updatePlayer(ServerPlayer player);
	@Unique private final Set<WaypointTransmitter> lodecore$movedWaypoints = new HashSet<>();
	@Unique private final Set<ServerPlayer> lodecore$movedPlayers = new HashSet<>();
	@Unique private boolean lodecore$flushing;

	@Inject(method = "addPlayer", at = @At("HEAD"), cancellable = true)
	private void lodecore$addRemoteTransmitter(ServerPlayer player, CallbackInfo ci) {
		if (!Hooks.isRemotePlayer(player)) return;
		players.remove(player);
		lodecore$movedPlayers.remove(player);
		if (player.isTransmittingWaypoint()) trackWaypoint(player);
		ci.cancel();
	}

	@Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
	private void lodecore$updateLocalReceiver(ServerPlayer player, CallbackInfo ci) {
		if (lodecore$flushing) return;
		if (Hooks.isRemotePlayer(player)) {
			players.remove(player);
			ci.cancel();
			return;
		}
		// Handoff promotes an existing mirror without re-adding its entity to the level.
		players.add(player);
		lodecore$movedPlayers.add(player);
		ci.cancel();
	}

	@Inject(method = "updateWaypoint", at = @At("HEAD"), cancellable = true)
	private void lodecore$deferWaypoint(WaypointTransmitter waypoint, CallbackInfo ci) {
		if (lodecore$flushing) return;
		lodecore$movedWaypoints.add(waypoint);
		ci.cancel();
	}

	@Override
	public void lodecore$flushWaypoints() {
		lodecore$flushing = true;
		try {
			for (WaypointTransmitter waypoint : lodecore$movedWaypoints) updateWaypoint(waypoint);
			for (ServerPlayer player : lodecore$movedPlayers) {
				if (players.contains(player) && !Hooks.isRemotePlayer(player)) updatePlayer(player);
			}
		} finally {
			lodecore$movedWaypoints.clear();
			lodecore$movedPlayers.clear();
			lodecore$flushing = false;
		}
	}
}
