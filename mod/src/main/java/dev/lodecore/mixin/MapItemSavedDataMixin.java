package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import dev.lodecore.shared.MapSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

@Mixin(MapItemSavedData.class)
abstract class MapItemSavedDataMixin {
	/** Every pixel a map changes, as the game notes it for the players who carry the map. */
	@Inject(method = "setColor", at = @At("TAIL"))
	private void lodecore$onPixel(int x, int y, byte color, CallbackInfo ci) {
		MapSync maps = Hooks.maps();

		if (maps != null) {
			maps.onPixel((MapItemSavedData) (Object) this, x, y);
		}
	}
}
