package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.block.entity.BlockEntity;

@Mixin(BlockEntity.class)
abstract class BlockEntityMixin {
	/** Block entities call this whenever what is in them changes. */
	@Inject(method = "setChanged()V", at = @At("HEAD"))
	private void lodecore$noteChange(CallbackInfo ci) {
		Hooks.onBlockEntityChanged((BlockEntity) (Object) this);
	}
}
