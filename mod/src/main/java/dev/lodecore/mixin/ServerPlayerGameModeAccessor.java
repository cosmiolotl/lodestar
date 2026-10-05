package dev.lodecore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;

@Mixin(ServerPlayerGameMode.class)
public interface ServerPlayerGameModeAccessor {
	@Accessor("isDestroyingBlock")
	boolean lodecore$isDestroyingBlock();

	@Accessor("isDestroyingBlock")
	void lodecore$setDestroyingBlock(boolean destroying);

	@Accessor("destroyProgressStart")
	int lodecore$destroyProgressStart();

	@Accessor("destroyProgressStart")
	void lodecore$setDestroyProgressStart(int tick);

	@Accessor("destroyPos")
	BlockPos lodecore$destroyPos();

	@Accessor("destroyPos")
	void lodecore$setDestroyPos(BlockPos pos);

	@Accessor("gameTicks")
	int lodecore$gameTicks();

	@Accessor("hasDelayedDestroy")
	boolean lodecore$hasDelayedDestroy();

	@Accessor("hasDelayedDestroy")
	void lodecore$setHasDelayedDestroy(boolean delayed);

	@Accessor("delayedDestroyPos")
	BlockPos lodecore$delayedDestroyPos();

	@Accessor("delayedDestroyPos")
	void lodecore$setDelayedDestroyPos(BlockPos pos);

	@Accessor("delayedTickStart")
	int lodecore$delayedTickStart();

	@Accessor("delayedTickStart")
	void lodecore$setDelayedTickStart(int tick);

	@Accessor("lastSentState")
	int lodecore$lastSentState();

	@Accessor("lastSentState")
	void lodecore$setLastSentState(int state);
}
