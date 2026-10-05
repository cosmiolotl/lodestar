package dev.lodecore.mixin;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.saveddata.maps.MapBanner;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapFrame;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

@Mixin(MapItemSavedData.class)
public interface MapItemSavedDataAccessor {
	@Accessor("bannerMarkers")
	Map<String, MapBanner> lodecore$bannerMarkers();

	@Accessor("frameMarkers")
	Map<String, MapFrame> lodecore$frameMarkers();

	@Invoker("addDecoration")
	void lodecore$addDecoration(
			Holder<MapDecorationType> type, @Nullable LevelAccessor level, String key, double x, double z, double yRot, @Nullable Component name);

	@Invoker("removeDecoration")
	void lodecore$removeDecoration(String key);
}
