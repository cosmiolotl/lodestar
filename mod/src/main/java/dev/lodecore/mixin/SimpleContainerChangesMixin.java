package dev.lodecore.mixin;

import dev.lodecore.ChangeHooks;
import dev.lodecore.storage.EntityInventory;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SimpleContainer.class)
abstract class SimpleContainerChangesMixin implements EntityInventory {
	@Unique private @Nullable Entity lodecore$owner;
	@Override public void lodecore$owner(@Nullable Entity entity) { lodecore$owner = entity; }

	@Inject(method = "setChanged", at = @At("HEAD"))
	private void lodecore$contents(CallbackInfo ci) {
		if (lodecore$owner != null) ChangeHooks.entityTick(lodecore$owner);
	}
	@Inject(method = "removeItemNoUpdate", at = @At("RETURN"))
	private void lodecore$removed(CallbackInfoReturnable<ItemStack> ci) {
		if (lodecore$owner != null && !ci.getReturnValue().isEmpty()) ChangeHooks.entityTick(lodecore$owner);
	}
}
