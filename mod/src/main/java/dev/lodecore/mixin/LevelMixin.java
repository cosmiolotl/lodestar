package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(Level.class)
abstract class LevelMixin {
	@ModifyVariable(
			method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
			at = @At("HEAD"),
			argsOnly = true,
			ordinal = 0)
	private int lodecore$quietInRegionsOwnedElsewhere(int updateFlags, BlockPos pos, BlockState blockState, int sameUpdateFlags, int updateLimit) {
		return Hooks.adjustFlags((Level) (Object) this, pos, updateFlags);
	}

	/** While another node has custody of a block entity, its block is that node's to change. */
	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), cancellable = true)
	private void lodecore$keepBlocksInUseElsewhere(BlockPos pos, BlockState state, int updateFlags, int updateLimit, CallbackInfoReturnable<Boolean> cir) {
		if (Hooks.isLocked((Level) (Object) this, pos)) {
			cir.setReturnValue(false);
		}
	}

	/**
	 * Block entities tick, and block events run, where the block entity is: on the node that has
	 * custody of it, if any, rather than on the owner of its chunk.
	 */
	@Inject(method = "shouldTickBlocksAt(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
	private void lodecore$tickBlockEntitiesWhereTheyAre(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		Boolean here = Hooks.mayTickBlockEntityAt((Level) (Object) this, pos);

		if (here != null) {
			cir.setReturnValue(here);
		}
	}
}
