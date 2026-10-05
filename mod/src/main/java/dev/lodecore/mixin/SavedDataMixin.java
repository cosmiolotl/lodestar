package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import dev.lodecore.shared.MapSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

@Mixin(SavedData.class)
abstract class SavedDataMixin {
	/** A map is saved when its pixels, banners or frames change. */
	@Inject(method = "setDirty()V", at = @At("HEAD"))
	private void lodecore$onDirty(CallbackInfo ci) {
		if ((Object) this instanceof MapItemSavedData map) {
			MapSync maps = Hooks.maps();

			if (maps != null) {
				maps.onDirty(map);
			}
		}
	}
}
