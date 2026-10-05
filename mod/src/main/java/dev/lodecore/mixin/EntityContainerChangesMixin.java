package dev.lodecore.mixin;

import dev.lodecore.ChangeHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Container contents can change even when a vehicle's section is not ticking. */
@Mixin({AbstractChestBoat.class, AbstractMinecartContainer.class})
abstract class EntityContainerChangesMixin {
	@Inject(method = {"setChanged", "setItem", "clearContent"}, at = @At("TAIL"))
	private void lodecore$inventory(CallbackInfo ci) { ChangeHooks.entityTick((Entity) (Object) this); }

	@Inject(method = {"removeItem", "removeItemNoUpdate"}, at = @At("RETURN"))
	private void lodecore$removedItem(CallbackInfoReturnable<ItemStack> ci) {
		if (!ci.getReturnValue().isEmpty()) ChangeHooks.entityTick((Entity) (Object) this);
	}
}
