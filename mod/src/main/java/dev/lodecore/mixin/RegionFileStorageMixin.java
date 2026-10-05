package dev.lodecore.mixin;

import java.io.IOException;
import dev.lodecore.storage.WorldStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RegionFileStorage.class)
abstract class RegionFileStorageMixin {
	@Shadow public abstract RegionStorageInfo info();

	@Inject(method = "read", at = @At("HEAD"), cancellable = true)
	private void lodecore$read(ChunkPos pos, CallbackInfoReturnable<CompoundTag> ci) throws IOException {
		WorldStorage.Loaded loaded = WorldStorage.load(info(), pos);
		if (loaded.present()) ci.setReturnValue(loaded.data());
	}

	@Inject(method = "scanChunk", at = @At("HEAD"), cancellable = true)
	private void lodecore$scan(ChunkPos pos, StreamTagVisitor visitor, CallbackInfo ci) throws IOException {
		WorldStorage.Loaded loaded = WorldStorage.load(info(), pos);
		if (loaded.present()) {
			if (loaded.data() != null) loaded.data().acceptAsRoot(visitor);
			ci.cancel();
		}
	}

	@Inject(method = "write", at = @At("HEAD"), cancellable = true)
	private void lodecore$write(ChunkPos pos, CompoundTag data, CallbackInfo ci) throws IOException {
		WorldStorage.save(info(), pos, data);
		if (WorldStorage.enabled()) ci.cancel();
	}
}
