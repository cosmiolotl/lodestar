package dev.lodecore.mixin;

import dev.lodecore.ChangeHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class EntityChangesMixin {
	@Inject(method = "setPosRaw", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/EntityInLevelCallback;onMove()V", shift = At.Shift.AFTER))
	private void lodecore$position(double x, double y, double z, CallbackInfo ci) {
		ChangeHooks.position((Entity) (Object) this);
	}
	@Inject(method = "setYRot", at = @At("HEAD"))
	private void lodecore$yaw(float value, CallbackInfo ci) {
		Entity entity = (Entity) (Object) this;
		if (entity.getYRot() != value) ChangeHooks.movement(entity);
	}
	@Inject(method = "setXRot", at = @At("HEAD"))
	private void lodecore$pitch(float value, CallbackInfo ci) {
		Entity entity = (Entity) (Object) this;
		if (entity.getXRot() != value) ChangeHooks.movement(entity);
	}
	@Inject(method = "setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"))
	private void lodecore$motion(Vec3 value, CallbackInfo ci) {
		Entity entity = (Entity) (Object) this;
		if (!entity.getDeltaMovement().equals(value)) ChangeHooks.movement(entity);
	}
	@Inject(method = "setOnGround(Z)V", at = @At("HEAD"))
	private void lodecore$ground(boolean grounded, CallbackInfo ci) {
		Entity entity = (Entity) (Object) this;
		if (entity.onGround() != grounded) ChangeHooks.movement(entity);
	}
	@Inject(method = "setOnGroundWithMovement(ZZLnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"))
	private void lodecore$groundMovement(boolean grounded, boolean collision, Vec3 movement, CallbackInfo ci) {
		Entity entity = (Entity) (Object) this;
		if (entity.onGround() != grounded) ChangeHooks.movement(entity);
	}
	@Inject(method = "stopRiding", at = @At("HEAD"))
	private void lodecore$previousVehicle(CallbackInfo ci) { ChangeHooks.entityTick((Entity) (Object) this); }

	@Inject(method = {"stopRiding", "load"}, at = @At("TAIL"))
	private void lodecore$state(CallbackInfo ci) { ChangeHooks.entity((Entity) (Object) this); }
	@Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At("RETURN"))
	private void lodecore$vehicle(CallbackInfoReturnable<Boolean> ci) {
		if (ci.getReturnValueZ()) ChangeHooks.movement((Entity) (Object) this);
	}
}
