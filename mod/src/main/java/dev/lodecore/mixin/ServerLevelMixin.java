package dev.lodecore.mixin;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import dev.lodecore.replication.EntityLookup;
import dev.lodecore.replication.Hooks;
import dev.lodecore.replication.TickRange;
import dev.lodecore.replication.WorldState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/** Only the owner of a region ticks it, and only an entity's authority ticks the entity. */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin implements TickRange, EntityLookup {
	@Unique
	private boolean lodecore$askingVanilla;

	@Shadow
	@Final
	private PersistentEntitySectionManager<Entity> entityManager;

	@Shadow
	@Final
	private EntityTickList entityTickList;

	@Shadow
	public abstract ServerChunkCache getChunkSource();

	@Override
	public boolean lodecore$hasEntity(UUID uuid) {
		return this.entityManager.isLoaded(uuid);
	}

	/** Everything that knows the entity by its id hears of the new one. */
	@Override
	@SuppressWarnings("unchecked")
	public void lodecore$reassignId(Entity entity, int id) {
		ServerChunkCache chunks = this.getChunkSource();
		boolean tracked = chunks.chunkMap.hasEntityWithId(entity.getId());
		boolean ticking = this.entityTickList.contains(entity);
		net.minecraft.world.level.entity.EntityLookup<Entity> lookup =
				(net.minecraft.world.level.entity.EntityLookup<Entity>) ((PersistentEntitySectionManagerAccessor) this.entityManager).lodecore$visibleEntityStorage();

		if (tracked) {
			chunks.removeEntity(entity);
		}

		if (ticking) {
			this.entityTickList.remove(entity);
		}

		lookup.remove(entity);
		entity.setId(id);
		lookup.add(entity);

		if (ticking) {
			this.entityTickList.add(entity);
		}

		if (tracked) {
			chunks.addEntity(entity);
		}
	}

	/** Entity ids come from this node's own blocks, so that each entity has the same id on every node. */
	@Redirect(method = "getNextEntityId", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/atomic/AtomicInteger;incrementAndGet()I"))
	private int lodecore$nextEntityId(AtomicInteger counter) {
		return Hooks.nextEntityId(counter);
	}

	@Shadow
	public abstract boolean shouldTickBlocksAt(long chunkPos);

	@Override
	public boolean lodecore$inTickRange(long chunkPos) {
		lodecore$askingVanilla = true;

		try {
			return this.shouldTickBlocksAt(chunkPos);
		} finally {
			lodecore$askingVanilla = false;
		}
	}

	/**
	 * Gates scheduled block and fluid ticks, block entities and block events. Those stay queued
	 * rather than being dropped, so they run if this node takes the region over.
	 */
	@Inject(method = "shouldTickBlocksAt(J)Z", at = @At("RETURN"), cancellable = true)
	private void lodecore$tickOnlyOwnedChunks(long chunkPos, CallbackInfoReturnable<Boolean> cir) {
		if (!lodecore$askingVanilla && cir.getReturnValueZ() && !Hooks.mayTick((ServerLevel) (Object) this, chunkPos)) {
			cir.setReturnValue(false);
		}
	}

	/** Gates random ticks and precipitation. */
	@Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
	private void lodecore$tickOnlyOwnedChunks(LevelChunk chunk, int tickSpeed, CallbackInfo ci) {
		if (!Hooks.mayTick((ServerLevel) (Object) this, chunk.getPos().pack())) {
			ci.cancel();
		}
	}

	/** A mirror moves when its authority says so, and not otherwise. */
	@Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
	private void lodecore$tickOnlyAuthoritativeEntities(Entity entity, CallbackInfo ci) {
		if (!Hooks.mayTick(entity)) {
			ci.cancel();
		}
	}

    @Inject(method = "tickNonPassenger", at = @At("TAIL"))
    private void lodecore$entityTicked(Entity entity, CallbackInfo ci) { dev.lodecore.ChangeHooks.entityTick(entity); }

    @Inject(method = "tickPassenger", at = @At("TAIL"))
    private void lodecore$passengerTicked(Entity vehicle, Entity passenger, CallbackInfo ci) { dev.lodecore.ChangeHooks.entityTick(passenger); }

	/** Phantoms, patrols, cats and the like: each owner spawns them for the regions it owns. */
	@Inject(method = "tickCustomSpawners", at = @At("HEAD"))
	private void lodecore$startSpawning(boolean spawnEnemies, CallbackInfo ci) {
		Hooks.setSpawning((ServerLevel) (Object) this, true);
	}

	@Inject(method = "tickCustomSpawners", at = @At("RETURN"))
	private void lodecore$stopSpawning(boolean spawnEnemies, CallbackInfo ci) {
		Hooks.setSpawning((ServerLevel) (Object) this, false);
	}

	/**
	 * Only the master of the cluster's state runs the weather cycle; the other nodes take on the
	 * weather it broadcasts. Rain and thunder still fade in and out here as the weather changes.
	 */
	@Redirect(
			method = "advanceWeatherCycle",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/gamerules/GameRules;get(Lnet/minecraft/world/level/gamerules/GameRule;)Ljava/lang/Object;"))
	private Object lodecore$advanceWeatherOnTheMaster(GameRules rules, GameRule<?> rule) {
		return Hooks.advancesWeather() ? rules.get(rule) : Boolean.FALSE;
	}

	/** Enough players slept through the night: the weather clears. */
	@Inject(method = "resetWeatherCycle", at = @At("TAIL"))
	private void lodecore$onWeatherReset(CallbackInfo ci) {
		Hooks.onWorldStateChanged(WorldState.WEATHER);
	}

	/** A map this node does not have, or has not taken from the cluster yet, is asked for. */
	@Inject(method = "getMapData", at = @At("RETURN"))
	private void lodecore$onMapLookup(MapId id, CallbackInfoReturnable<MapItemSavedData> cir) {
		Hooks.onMapLookup(id, cir.getReturnValue());
	}

	@Inject(method = "setMapData", at = @At("TAIL"))
	private void lodecore$onMapSet(MapId id, MapItemSavedData map, CallbackInfo ci) {
		Hooks.onMapSet(id, map);
	}

	/** Map ids come from this node's own blocks, so that no two nodes make different maps with the same id. */
	@Inject(method = "getFreeMapId", at = @At("HEAD"), cancellable = true)
	private void lodecore$nextMapId(CallbackInfoReturnable<MapId> cir) {
		MapId id = Hooks.nextMapId();

		if (id != null) {
			cir.setReturnValue(id);
		}
	}

	/** An entity created in a region another node owns is created by that node instead. */
	@Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
	private void lodecore$addOnlyWhereOwned(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (Hooks.interceptNewEntity((ServerLevel) (Object) this, entity)) {
			cir.setReturnValue(false);
		}
	}
}
