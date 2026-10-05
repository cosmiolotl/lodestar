package dev.lodecore.mixin;

import dev.lodecore.ChangeHooks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkAccess.class)
abstract class ChunkChangesMixin {
	@Inject(method = "markUnsaved", at = @At("TAIL"))
	private void lodecore$changed(CallbackInfo ci) {
		if ((Object) this instanceof LevelChunk chunk) ChangeHooks.chunk(chunk);
	}
}
