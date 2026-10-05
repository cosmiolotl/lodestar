package dev.lodecore.mixin;

import java.nio.file.Path;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.stats.ServerStatsCounter;

@Mixin(ServerStatsCounter.class)
public interface ServerStatsCounterAccessor {
	@org.spongepowered.asm.mixin.gen.Invoker("toJson")
	com.google.gson.JsonElement lodecore$toJson();

	@Accessor("file")
	Path lodecore$file();
}
