package dev.lodecore.mixin;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.dimension.end.DragonRespawnStage;
import net.minecraft.world.level.dimension.end.EnderDragonFight;

@Mixin(EnderDragonFight.class)
public interface EnderDragonFightAccessor {
	@Accessor("level")
	ServerLevel lodecore$level();

	@Accessor("origin")
	BlockPos lodecore$origin();

	@Accessor("dragonEvent")
	ServerBossEvent lodecore$dragonEvent();

	@Accessor("ticksSinceLastPlayerScan")
	int lodecore$ticksSinceLastPlayerScan();

	@Accessor("ticksSinceLastPlayerScan")
	void lodecore$setTicksSinceLastPlayerScan(int ticks);

	@Accessor("needsStateScanning")
	boolean lodecore$needsStateScanning();

	@Accessor("needsStateScanning")
	void lodecore$setNeedsStateScanning(boolean needsStateScanning);

	@Accessor("dragonKilled")
	boolean lodecore$dragonKilled();

	@Accessor("dragonKilled")
	void lodecore$setDragonKilled(boolean dragonKilled);

	@Accessor("hasPreviouslyKilledDragon")
	boolean lodecore$previouslyKilled();

	@Accessor("hasPreviouslyKilledDragon")
	void lodecore$setPreviouslyKilled(boolean previouslyKilled);

	@Accessor("respawnStage")
	@Nullable DragonRespawnStage lodecore$respawnStage();

	@Accessor("respawnStage")
	void lodecore$setRespawnStage(@Nullable DragonRespawnStage stage);

	@Accessor("respawnTime")
	int lodecore$respawnTime();

	@Accessor("respawnTime")
	void lodecore$setRespawnTime(int respawnTime);

	@Accessor("dragonUUID")
	@Nullable UUID lodecore$dragonUUID();

	@Accessor("dragonUUID")
	void lodecore$setDragonUUID(@Nullable UUID dragon);

	@Accessor("exitPortalLocation")
	@Nullable BlockPos lodecore$exitPortalLocation();

	@Accessor("exitPortalLocation")
	void lodecore$setExitPortalLocation(@Nullable BlockPos location);

	@Accessor("gateways")
	List<Integer> lodecore$gateways();

	@Accessor("respawnCrystals")
	List<EntityReference<EndCrystal>> lodecore$respawnCrystals();

	@Accessor("respawnCrystals")
	void lodecore$setRespawnCrystals(List<EntityReference<EndCrystal>> crystals);

	@Invoker("updatePlayers")
	void lodecore$updatePlayers();
}
