package dev.lodecore.mixin;

import java.nio.file.Path;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.PlayerAdvancements;

@Mixin(PlayerAdvancements.class)
public interface PlayerAdvancementsAccessor {
	@Accessor("progress")
	java.util.Map<net.minecraft.advancements.AdvancementHolder, net.minecraft.advancements.AdvancementProgress> lodecore$progress();

	@Accessor("playerSavePath")
	Path lodecore$playerSavePath();
}
