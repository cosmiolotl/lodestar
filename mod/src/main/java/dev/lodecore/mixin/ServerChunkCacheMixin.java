package dev.lodecore.mixin;

import java.util.List;

import dev.lodecore.replication.Hooks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;

@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheMixin {
	@Shadow
	@Final
	private ServerLevel level;

	/** Natural spawning, lightning and a chunk's inhabited time belong to the owner of its region. */
	@Inject(method = "tickSpawningChunk", at = @At("HEAD"), cancellable = true)
	private void lodecore$spawnOnlyInOwnedChunks(LevelChunk chunk, List<MobCategory> categories, NaturalSpawner.SpawnState state, CallbackInfo ci) {
		if (!Hooks.mayTick(this.level, chunk.getPos().pack())) {
			ci.cancel();
		}
	}
}
