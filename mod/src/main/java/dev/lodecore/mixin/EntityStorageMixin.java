package dev.lodecore.mixin;

import java.util.concurrent.CompletableFuture;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.entity.ChunkEntities;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityStorage.class)
abstract class EntityStorageMixin {
	@Shadow @Final private LongSet emptyChunks;

	@Inject(method = "storeEntities", at = @At("HEAD"))
	private void lodecore$storeEmptyTombstone(ChunkEntities<Entity> chunk, CallbackInfo ci) {
		emptyChunks.remove(chunk.getPos().pack());
	}

	@Inject(method = "loadEntities", at = @At("HEAD"))
	private void lodecore$invalidateEmptyCache(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<ChunkEntities<Entity>>> ci) {
		// Another owner can populate a chunk while it is unloaded here.
		emptyChunks.remove(pos.pack());
	}
}
