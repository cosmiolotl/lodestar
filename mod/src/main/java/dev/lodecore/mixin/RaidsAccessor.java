package dev.lodecore.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;

@Mixin(Raids.class)
public interface RaidsAccessor {
	@Accessor("raidMap")
	Int2ObjectMap<Raid> lodecore$raidMap();

	@Accessor("nextId")
	int lodecore$nextId();

	@Accessor("nextId")
	void lodecore$setNextId(int nextId);

	@Invoker("getOrCreateRaid")
	Raid lodecore$getOrCreateRaid(ServerLevel level, BlockPos pos);

	@Invoker("getUniqueId")
	int lodecore$getUniqueId();
}
