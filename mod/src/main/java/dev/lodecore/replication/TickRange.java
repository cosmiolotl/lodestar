package dev.lodecore.replication;

/** Implemented by {@code ServerLevel} through {@code ServerLevelMixin}. */
public interface TickRange {
	/** Whether the game itself would tick blocks in a chunk, leaving aside who owns it. */
	boolean lodecore$inTickRange(long chunkPos);
}
