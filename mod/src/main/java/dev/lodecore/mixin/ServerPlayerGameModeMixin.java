package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerGameModeMixin {
	@Shadow
	@Final
	protected ServerPlayer player;

	@Inject(method = "changeGameModeForPlayer", at = @At("RETURN"))
	private void lodecore$visibilityChanged(CallbackInfoReturnable<Boolean> ci) {
		if (ci.getReturnValueZ()) dev.lodecore.ChangeHooks.entity(player);
	}

	/** Using a block entity another node is the authority for waits for custody of it. */
	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void lodecore$useWithCustody(
			ServerPlayer player, Level level, ItemStack itemStack, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
		InteractionResult instead = Hooks.useItemOn(player, level, hand, hitResult);

		if (instead != null) {
			cir.setReturnValue(instead);
		}
	}

	/** So does breaking it: what it drops comes from what is in it. */
	@Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
	private void lodecore$destroyWithCustody(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (Hooks.destroyBlock(this.player, pos)) {
			cir.setReturnValue(true);
		}
	}
}
