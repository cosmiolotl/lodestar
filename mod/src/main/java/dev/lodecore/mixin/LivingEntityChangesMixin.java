package dev.lodecore.mixin;

import dev.lodecore.ChangeHooks;
import java.util.Map;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
abstract class LivingEntityChangesMixin {
	@Unique private float lodecore$headRotation = Float.NaN;

	// AI controllers also write yHeadRot directly, without calling its setter.
	@Inject(method = "tick", at = @At("TAIL"))
	private void lodecore$rotationAfterTick(CallbackInfo ci) {
		LivingEntity entity = (LivingEntity) (Object) this;
		float rotation = entity.getYHeadRot();
		if (rotation == lodecore$headRotation) return;
		lodecore$headRotation = rotation;
		ChangeHooks.movement(entity);
	}

	@Inject(method = "setYHeadRot", at = @At("HEAD"))
	private void lodecore$head(float value, CallbackInfo ci) {
		LivingEntity entity = (LivingEntity) (Object) this;
		if (entity.getYHeadRot() != value) ChangeHooks.movement(entity);
	}
	@Inject(method = "onEquipItem", at = @At("TAIL"))
	private void lodecore$equipment(CallbackInfo ci) { ChangeHooks.equipment((LivingEntity) (Object) this); }
	// Vanilla also catches in-place ItemStack mutations and hand swaps here.
	@Inject(method = "collectEquipmentChanges", at = @At("RETURN"))
	private void lodecore$equipmentChanges(CallbackInfoReturnable<Map<EquipmentSlot, ItemStack>> ci) {
		if (ci.getReturnValue() != null) ChangeHooks.equipment((LivingEntity) (Object) this);
	}
}
