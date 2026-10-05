package dev.lodecore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.saveddata.maps.MapIndex;

@Mixin(MapIndex.class)
public interface MapIndexAccessor {
	@Accessor("lastMapId")
	int lodecore$lastMapId();

	@Accessor("lastMapId")
	void lodecore$setLastMapId(int id);
}
