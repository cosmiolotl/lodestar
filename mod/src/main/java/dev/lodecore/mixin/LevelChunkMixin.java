package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
	@Shadow
	@Final
	private Level level;

	/** Every block change in a loaded chunk ends up here, whatever caused it. */
	@Inject(method = "setBlockState", at = @At("RETURN"))
	private void lodecore$noteChange(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
		// Null means nothing changed. The block is read back rather than taken from the call:
		// placing it can have changed it again already, and that later change has been noted.
		if (cir.getReturnValue() != null) {
			Hooks.onBlockChanged(this.level, pos, ((LevelChunk) (Object) this).getBlockState(pos));
		}
	}

	/** A block entity is put in a chunk: loaded with it, made by a placed block, or replaced. */
	@Inject(method = "setBlockEntity", at = @At("HEAD"))
	private void lodecore$noteBlockEntity(BlockEntity blockEntity, CallbackInfo ci) {
		Hooks.onBlockEntityMade(this.level, blockEntity);
	}
}
