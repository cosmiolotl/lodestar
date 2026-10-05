package dev.lodecore.mixin;

import dev.lodecore.ChangeHooks;
import dev.lodecore.storage.EntityInventory;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractHorse.class)
abstract class HorseInventoryChangesMixin {
	@Shadow protected SimpleContainer inventory;

	@Inject(method = "createInventory", at = @At("TAIL"))
	private void lodecore$observeInventory(CallbackInfo ci) {
		((EntityInventory) inventory).lodecore$owner((AbstractHorse) (Object) this);
		ChangeHooks.entityTick((AbstractHorse) (Object) this);
	}
}
