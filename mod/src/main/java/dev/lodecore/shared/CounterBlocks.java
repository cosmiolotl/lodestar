package dev.lodecore.shared;

import dev.lodecore.net.Wire;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;

/**
 * Hands out the ids of one of lodestar's counters, such as map ids, so that no two nodes hand out
 * the same id. lodestar hands each node blocks of ids of its own, and the node asks for the next
 * block when it is half way through its current one.
 *
 * <p>Runs on the game thread.
 */
public final class CounterBlocks {
	private static final int BLOCK_SIZE = 1 << Wire.COUNTER_BLOCK_SHIFT;

	private final int counter;
	private final IntArrayFIFOQueue spare = new IntArrayFIFOQueue();
	/** The current block's ids: from {@code start}, up to {@code end}; {@code next} is the next to hand out. */
	private long start;
	private long next;
	private long end;
	private boolean asked;

	public CounterBlocks(int counter) {
		this.counter = counter;
	}

	/**
	 * The next id that is at least {@code floor}, or -1 if this node has none left: it has not been
	 * given a block yet, or has used up all it was given.
	 */
	public int next(int floor) {
		while (true) {
			if (next >= end) {
				if (spare.isEmpty()) {
					return -1;
				}

				begin(spare.dequeueInt());
			}

			if (next >= floor) {
				return (int) next++;
			}

			next = Math.min(floor, end);
		}
	}

	private void begin(int block) {
		start = (long) block << Wire.COUNTER_BLOCK_SHIFT;
		next = start;
		end = Math.min(start + BLOCK_SIZE, (long) Integer.MAX_VALUE + 1);
	}

	/** Whether to ask lodestar for another block now: none is to spare, and the current one is half used or used up. */
	public boolean shouldAsk() {
		return !asked && spare.isEmpty() && (next >= end || next - start >= BLOCK_SIZE / 2);
	}

	/** The request for another block, past {@code floor}: what this node may have handed out on its own. */
	public byte[] ask(int floor) {
		asked = true;
		return Wire.counterBlockRequest(counter, Math.max(floor, 0));
	}

	public void onBlock(int block) {
		asked = false;
		spare.enqueue(block);
	}

	/** An answer that was on its way is lost with the link; blocks already here stay this node's. */
	public void onDisconnected() {
		asked = false;
	}
}
