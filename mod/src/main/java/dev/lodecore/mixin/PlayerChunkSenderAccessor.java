package dev.lodecore.mixin;

import it.unimi.dsi.fastutil.longs.LongSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.network.PlayerChunkSender;

@Mixin(PlayerChunkSender.class)
public interface PlayerChunkSenderAccessor {
	@Accessor("pendingChunks")
	LongSet lodecore$pendingChunks();

	@Accessor("desiredChunksPerTick")
	float lodecore$desiredChunksPerTick();

	@Accessor("desiredChunksPerTick")
	void lodecore$setDesiredChunksPerTick(float chunks);

	@Accessor("batchQuota")
	float lodecore$batchQuota();

	@Accessor("batchQuota")
	void lodecore$setBatchQuota(float quota);

	@Accessor("unacknowledgedBatches")
	int lodecore$unacknowledgedBatches();

	@Accessor("unacknowledgedBatches")
	void lodecore$setUnacknowledgedBatches(int batches);

	@Accessor("maxUnacknowledgedBatches")
	int lodecore$maxUnacknowledgedBatches();

	@Accessor("maxUnacknowledgedBatches")
	void lodecore$setMaxUnacknowledgedBatches(int batches);
}
