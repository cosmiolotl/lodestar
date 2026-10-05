package dev.lodecore.mixin;

import java.util.Set;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;

@Mixin(Raid.class)
public interface RaidAccessor {
	@Accessor("started")
	void lodecore$setStarted(boolean started);

	@Accessor("active")
	void lodecore$setActive(boolean active);

	@Accessor("ticksActive")
	long lodecore$ticksActive();

	@Accessor("ticksActive")
	void lodecore$setTicksActive(long ticksActive);

	@Accessor("groupsSpawned")
	void lodecore$setGroupsSpawned(int groupsSpawned);

	@Accessor("raidCooldownTicks")
	int lodecore$raidCooldownTicks();

	@Accessor("raidCooldownTicks")
	void lodecore$setRaidCooldownTicks(int ticks);

	@Accessor("postRaidTicks")
	int lodecore$postRaidTicks();

	@Accessor("postRaidTicks")
	void lodecore$setPostRaidTicks(int ticks);

	@Accessor("totalHealth")
	void lodecore$setTotalHealth(float totalHealth);

	@Accessor("numGroups")
	int lodecore$numGroups();

	@Mutable
	@Accessor("numGroups")
	void lodecore$setNumGroups(int numGroups);

	@Accessor("center")
	void lodecore$setCenter(BlockPos center);

	@Accessor("heroesOfTheVillage")
	Set<UUID> lodecore$heroesOfTheVillage();

	@Accessor("raidEvent")
	ServerBossEvent lodecore$raidEvent();

	@Accessor("celebrationTicks")
	int lodecore$celebrationTicks();

	@Accessor("celebrationTicks")
	void lodecore$setCelebrationTicks(int ticks);

	@Invoker("updatePlayers")
	void lodecore$updatePlayers(ServerLevel level);

	@Invoker("playSound")
	void lodecore$playSound(ServerLevel level, BlockPos origin);
}
