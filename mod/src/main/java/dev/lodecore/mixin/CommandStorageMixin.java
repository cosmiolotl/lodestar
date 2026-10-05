package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import dev.lodecore.shared.SharedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.CommandStorage;

@Mixin(CommandStorage.class)
abstract class CommandStorageMixin {
	/** {@code /data} changes command storage by reading it, changing a copy and setting it back. */
	@Inject(method = "set", at = @At("TAIL"))
	private void lodecore$onSet(Identifier id, CompoundTag contents, CallbackInfo ci) {
		SharedData shared = Hooks.shared();

		if (shared != null) {
			shared.serverData().onStorageChanged(id);
		}
	}
}
