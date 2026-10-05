package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import net.fabricmc.fabric.impl.lookup.block.BlockApiLookupImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fabric's transfer API, which hoppers and other mods' pipes move items and fluids through,
 * finds nothing at a block entity this node is not the authority for: what is in it is not this
 * node's to move.
 */
@Mixin(value = BlockApiLookupImpl.class, remap = false)
abstract class BlockApiLookupImplMixin {
	@Inject(method = "find", at = @At("RETURN"), cancellable = true)
	private void lodecore$nothingInForeignBlockEntities(
			Level level, BlockPos pos, BlockState state, BlockEntity blockEntity, Object context, CallbackInfoReturnable<Object> cir) {
		if (cir.getReturnValue() != null && Hooks.isForeignBlockEntity(level, pos)) {
			cir.setReturnValue(null);
		}
	}
}
