package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import net.fabricmc.fabric.impl.lookup.block.BlockApiCacheImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** The same, for lookups cached at a position. */
@Mixin(value = BlockApiCacheImpl.class, remap = false)
abstract class BlockApiCacheImplMixin {
	@Shadow
	public abstract ServerLevel getLevel();

	@Shadow
	public abstract BlockPos getPos();

	@Inject(method = "find", at = @At("RETURN"), cancellable = true)
	private void lodecore$nothingInForeignBlockEntities(BlockState state, Object context, CallbackInfoReturnable<Object> cir) {
		if (cir.getReturnValue() != null && Hooks.isForeignBlockEntity(this.getLevel(), this.getPos())) {
			cir.setReturnValue(null);
		}
	}
}
