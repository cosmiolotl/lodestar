package dev.lodecore.mixin;

import java.util.List;

import dev.lodecore.replication.Hooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Hoppers, droppers and crafters only move items in and out of what this node is the authority
 * for. A chest another node has custody of is not there, as far as they can tell.
 */
@Mixin(HopperBlockEntity.class)
abstract class HopperBlockEntityMixin {
	@Inject(
			method = "getContainerAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;DDD)Lnet/minecraft/world/Container;",
			at = @At("RETURN"),
			cancellable = true)
	private static void lodecore$onlyAuthoritativeContainers(
			Level level, BlockPos pos, BlockState state, double x, double y, double z, CallbackInfoReturnable<Container> cir) {
		Container container = cir.getReturnValue();

		if (container != null) {
			cir.setReturnValue(Hooks.filterContainer(level, container));
		}
	}

	@Inject(method = "getItemsAtAndAbove", at = @At("RETURN"), cancellable = true)
	private static void lodecore$onlyAuthoritativeItems(Level level, Hopper hopper, CallbackInfoReturnable<List<ItemEntity>> cir) {
		cir.setReturnValue(Hooks.filterItems(level, cir.getReturnValue()));
	}
}
