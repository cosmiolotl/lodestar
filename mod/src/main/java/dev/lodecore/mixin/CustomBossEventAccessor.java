package dev.lodecore.mixin;

import java.util.Set;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.bossevents.CustomBossEvent;

@Mixin(CustomBossEvent.class)
public interface CustomBossEventAccessor {
	/** Every player on the bar, here or not. */
	@Accessor("players")
	Set<UUID> lodecore$players();
}
