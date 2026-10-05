package dev.lodecore.replication;

import dev.lodecore.net.Wire;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;

/**
 * Hands out entity ids so that an entity has the same id on every node.
 *
 * <p>A client knows entities by id, its own player included. For a player to move from one node
 * to another without their client noticing, every entity the client knows must have the id there
 * that it had on the node the player left. So lodestar hands each node blocks of ids of its own,
 * every entity a node makes takes the next id from its block, and a mirror takes the id its
 * authority gave the entity. Entities made before this node has a block of its own get ids from
 * block 0, which every node shares, and are given new ones from the first block lodestar hands
 * out, before any player can arrive.
 *
 * <p>Ids can be asked for on any thread: entities are made while chunks are generated too.
 */
public final class EntityIds {
	private static final int BLOCK_SIZE = 1 << Wire.ID_BLOCK_SHIFT;

	/** The block being handed out from, 0 until lodestar has given one. */
	private int block;
	private int next = 1;
	private long end = BLOCK_SIZE;
	private final IntArrayFIFOQueue spare = new IntArrayFIFOQueue();
	private boolean asked;

	public synchronized int next() {
		if (next >= end) {
			if (!spare.isEmpty()) {
				start(spare.dequeueInt());
			} else {
				// Out of ids with no new block yet: go round this block again. The level skips
				// the ids its entities still have.
				next = Math.max(1, block << Wire.ID_BLOCK_SHIFT);
			}
		}

		return next++;
	}

	private void start(int block) {
		this.block = block;
		this.next = Math.max(1, block << Wire.ID_BLOCK_SHIFT);
		this.end = Math.min(((long) block << Wire.ID_BLOCK_SHIFT) + BLOCK_SIZE, Integer.MAX_VALUE);
	}

	/** Whether this node has a block of its own yet. */
	public synchronized boolean hasBlock() {
		return block != 0;
	}

	/** Whether to ask lodestar for another block now: when there is none to spare and the current one is half used. */
	public synchronized boolean shouldAsk() {
		return !asked && spare.isEmpty() && (block == 0 || next - ((long) block << Wire.ID_BLOCK_SHIFT) >= BLOCK_SIZE / 2);
	}

	public synchronized void onAsked() {
		asked = true;
	}

	/**
	 * Takes a block lodestar handed out.
	 *
	 * @return whether it is this node's first, which the entities made so far move into
	 */
	public synchronized boolean onBlock(int block) {
		asked = false;

		if (this.block == 0) {
			start(block);
			return true;
		}

		spare.enqueue(block);
		return false;
	}

	/** An answer that was on its way is lost with the link; blocks already here stay this node's. */
	public synchronized void onDisconnected() {
		asked = false;
	}

	/** Whether an id is in shared block 0, which entities made before this node had a block have. */
	public static boolean isShared(int id) {
		return id < BLOCK_SIZE;
	}

	/** Whether no entity in a level has an id. */
	public static boolean isFree(ServerLevel level, int id) {
		return level.getEntity(id) == null && !level.getChunkSource().chunkMap.hasEntityWithId(id);
	}

	/**
	 * Gives an entity in a level another id, if no other entity there has it. The players that
	 * can see the entity are told it went and came back under its new id.
	 *
	 * @return whether the entity has that id now
	 */
	public static boolean reassign(ServerLevel level, Entity entity, int id) {
		if (entity.getId() == id) {
			return true;
		}

		// A dragon's parts take the ids after the dragon's.
		if (entity instanceof EnderDragon || entity instanceof EnderDragonPart || !isFree(level, id)) {
			return false;
		}

		((EntityLookup) level).lodecore$reassignId(entity, id);
		return true;
	}
}
