package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import dev.lodecore.replication.TrackingIndex;
import dev.lodecore.replication.TrackingUpdates;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import java.util.List;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
abstract class ChunkMapMixin implements TrackingIndex {
	@Shadow
	@Final
	private ServerLevel level;
	@Shadow @Final private Int2ObjectMap<?> entityMap;
	@Unique private final TrackingUpdates lodecore$tracking = new TrackingUpdates();

	/** Chunk tickets still update immediately; repeated visibility scans wait for the entity tick. */
	@Redirect(method = "move", at = @At(value = "INVOKE",
			target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;"))
	private ObjectCollection<?> lodecore$deferVisibilityChecks(Int2ObjectMap<?> entities, ServerPlayer player) {
		lodecore$tracking.moved(player);
		return ObjectSets.emptySet();
	}

	@Override public void lodecore$moved(Entity entity) {
		Object entry = entityMap.get(entity.getId());
		if (entry == null) return;
		var tracked = (TrackedEntityAccessor) entry;
		if (entity instanceof ServerPlayer || tracked.lodecore$lastSection().asLong() != net.minecraft.core.SectionPos.asLong(entity.blockPosition())) {
			lodecore$tracking.moved(entity);
		}
	}

	@Inject(method = "addEntity", at = @At("TAIL"))
	private void lodecore$index(Entity entity, CallbackInfo ci) {
		if (entityMap.containsKey(entity.getId())) lodecore$tracking.added(entity);
	}

	@Inject(method = "removeEntity", at = @At("HEAD"))
	private void lodecore$unindex(Entity entity, CallbackInfo ci) { lodecore$tracking.removed(entity); }

	@Redirect(method = "tick()V", at = @At(value = "INVOKE", ordinal = 0,
			target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;"))
	private ObjectCollection<?> lodecore$combinedVisibility(Int2ObjectMap<?> entries) {
		// Chunk tracking views have already advanced; pair entities before sending deltas.
		lodecore$tracking.flush(level, entityMap);
		return entries.values();
	}

	@Redirect(method = "tick()V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;needsSync:Z"))
	private boolean lodecore$tickMirrorTracker(Entity entity) {
		// Tracking must advance even outside the local simulation distance. Do not
		// set needsSync on the entity: that would force a packet on every replica move.
		return entity.needsSync || Hooks.isMirror(entity);
	}

	@Redirect(method = "tick()V", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;updatePlayers(Ljava/util/List;)V"))
	private void lodecore$alreadyChecked(@Coerce Object tracked, List<ServerPlayer> players) { }

	@Redirect(method = "tick()V", at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z"))
	private boolean lodecore$skipSecondPass(List<?> players) { return true; }

	/**
	 * A remote player loads no chunks, and so does not count as a player for spawning, except
	 * where this node owns the ground it stands on.
	 */
	@Inject(method = "skipPlayer", at = @At("HEAD"), cancellable = true)
	private void lodecore$remotePlayersLoadNothingElsewhere(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
		if (Hooks.isRemotePlayerElsewhere(this.level, player)) {
			cir.setReturnValue(true);
		}
	}

	/** A remote player's client is connected to another node, so it is sent no chunks from here. */
	@Inject(method = "updateChunkTracking", at = @At("HEAD"), cancellable = true)
	private void lodecore$sendRemotePlayersNoChunks(ServerPlayer player, CallbackInfo ci) {
		if (Hooks.isRemotePlayer(player)) {
			ci.cancel();
		}
	}

	/** What the game shows for an entity is shown by its mirrors too. */
	@Inject(method = {"sendToTrackingPlayers", "sendToTrackingPlayersAndSelf"}, at = @At("HEAD"))
	private void lodecore$replicateEffects(Entity entity, Packet<?> packet, CallbackInfo ci) {
		Hooks.onEntityPacket(this.level, entity, packet);
	}
}
