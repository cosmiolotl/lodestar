package dev.lodecore.replication;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.lodecore.Node;
import dev.lodecore.net.Wire;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Keeps the blocks of a dimension the same on every node that has them loaded.
 *
 * <p>One node owns each region in use and is the only one that ticks it. How a block change
 * travels depends on where it happens:
 *
 * <ul>
 * <li>On the owner, it is a fact. It is published as a <em>change</em>, and replicas apply it
 * quietly: no neighbour updates, no side effects, because the owner has already run those and
 * publishes whatever came of them.
 * <li>On a replica (a player there breaks a block, say), it is a request. The replica applies it
 * quietly as a prediction and publishes it as an <em>intent</em>. The owner applies an intent as
 * an ordinary block update, so its consequences happen exactly once, on the owner, and reach
 * everyone as changes. Other replicas mirror the intent quietly.
 * </ul>
 *
 * <p>Nodes tick in lockstep. What a node changes during a tick goes out at the end of it, and
 * every other node applies it at the start of the next, before doing anything else.
 *
 * <p>Changes travel per chunk, to the nodes that have that chunk loaded. A node that loads a
 * chunk asks the owner for a <em>snapshot</em> of its blocks, block entities and entities and
 * adopts whatever differs from its own copy, and then tells lodestar that its copy is
 * <em>synced</em>. lodestar only hands a region to a node that has a synced copy of all of it.
 *
 * <p>An intent for a block that has a block entity, placed by a player here, carries the block
 * entity with it. And while another node has custody of a block entity, nothing here may change
 * its block but that node's intents: it is in use there.
 *
 * <p>Everything here runs on the game thread.
 */
public final class BlockReplication {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/blocks");

	/** Tell clients, and nothing else: no neighbour or shape updates, drops, or block callbacks. */
	private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

	// Payloads published to a chunk's subscribers.
	static final int CHANGES = 1;
	static final int INTENTS = 2;

	private final Node node;
	/** Set while applying what another node sent, so that it is not sent back out. */
	private boolean applyingRemote;
	/** The node whose intents are being applied, if any. */
	private int applyingIntentsOf = Node.NO_NODE;
	/**
	 * Blocks changed by this node since the last flush, by chunk, in the order they changed, with
	 * the state each was left in. The state is taken when the change happens: on a replica, the
	 * owner's copy can overwrite the block before the flush, and what goes out must still be what
	 * this node asked for.
	 */
	private final Map<ResourceKey<Level>, Long2ObjectMap<Long2IntMap>> dirty = new HashMap<>();
	private final BlockSnapshots snapshots;

	record BlockUpdate(BlockPos pos, BlockState state) {
	}

	public BlockReplication(Node node) {
		this.node = node;
		this.snapshots = new BlockSnapshots(node, this);
	}

	/**
	 * Called for every {@code Level.setBlock} on the server. In a region this node does not own,
	 * blocks only take quiet updates; the owner is the one to run the consequences.
	 */
	int adjustFlags(ServerLevel level, BlockPos pos, int flags) {
		if (applyingRemote || node.owns(level, ChunkPos.pack(pos))) {
			return flags;
		}

		return (flags & ~(Block.UPDATE_NEIGHBORS | Block.UPDATE_MOVE_BY_PISTON)) | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
	}

	public boolean isApplyingRemote() {
		return applyingRemote;
	}

	/**
	 * Whether a block may not change here: its block entity is in another node's custody, and
	 * the change is not that node's own.
	 */
	boolean isLocked(ServerLevel level, BlockPos pos) {
		if (!node.isConnected() || applyingRemote) {
			return false;
		}

		int holder = node.custody().holderOf(level, pos);
		return holder != Node.NO_NODE && holder != node.nodeId() && holder != applyingIntentsOf;
	}

	/** Called whenever a block in a loaded chunk has actually changed. */
	void onBlockChanged(ServerLevel level, BlockPos pos, BlockState state) {
		if (applyingRemote || !node.isConnected()) {
			return;
		}

		dirty.computeIfAbsent(level.dimension(), key -> new Long2ObjectOpenHashMap<>())
				.computeIfAbsent(ChunkPos.pack(pos), key -> new Long2IntLinkedOpenHashMap())
				.put(pos.asLong(), Block.getId(state));
	}

	/** Sends out what changed this tick. */
	public void flushChanges() {
		for (Map.Entry<ResourceKey<Level>, Long2ObjectMap<Long2IntMap>> entry : dirty.entrySet()) {
			ServerLevel level = node.server().getLevel(entry.getKey());
			int dimension = node.dimensionId(entry.getKey());

			if (level != null && dimension >= 0) {
				for (Long2ObjectMap.Entry<Long2IntMap> chunk : entry.getValue().long2ObjectEntrySet()) {
					publish(level, dimension, chunk.getLongKey(), chunk.getValue());
				}
			}

			entry.getValue().clear();
		}
	}

	public void answerSnapshotRequests() { snapshots.answerSnapshotRequests(); }

	private void publish(ServerLevel level, int dimension, long chunk, Long2IntMap updates) {
		// Only where each block ended up this tick goes out, not every state it passed through.
		// The states were taken as they changed, so this is safe even for a chunk that is being
		// unloaded meanwhile.
		boolean owned = node.owns(level, chunk);
		FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer(1 + 5 + updates.size() * 11));
		out.writeByte(owned ? CHANGES : INTENTS);
		out.writeVarInt(updates.size());
		List<CompoundTag> made = new ArrayList<>();

		for (Long2IntMap.Entry update : updates.long2IntEntrySet()) {
			out.writeLong(update.getLongKey());
			out.writeVarInt(update.getIntValue());

			CompoundTag blockEntity = owned ? null : node.blockEntities().takeMade(level, BlockPos.of(update.getLongKey()));

			if (blockEntity != null) {
				made.add(blockEntity);
			}
		}

		out.writeVarInt(made.size());

		for (CompoundTag blockEntity : made) {
			out.writeNbt(blockEntity);
		}

		node.send(Wire.publish(dimension, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), ByteBufUtil.getBytes(out)));
	}

	/** Another subscriber of a chunk published something. */
	public void onRelay(int from, ServerLevel level, ChunkPos chunkPos, ByteBuffer payload) {
		if (level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z()) == null) {
			// The chunk is on its way out: it has lost its tickets but is not unloaded yet. If
			// it gets its tickets back before it goes, nothing announces it as loaded again,
			// so note that it has missed a change and catch up when it is back in use. lodestar
			// goes on counting it as synced meanwhile: a handover to this node in that window
			// would take over a stale chunk.
			LongSet syncedChunks = snapshots.synced.get(level.dimension());

			if (syncedChunks != null) {
				syncedChunks.remove(chunkPos.pack());
			}

			return;
		}

		try {
			FriendlyByteBuf in = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
			int kind = in.readByte();
			List<BlockUpdate> updates = readUpdates(in, chunkPos);
			List<CompoundTag> made = readMade(in);

			switch (kind) {
				case CHANGES -> {
					// Only the owner states facts.
					if (from == node.ownerOf(level.dimension(), chunkPos.pack())) {
						applyQuietly(level, updates);
					}
				}
				case INTENTS -> {
					if (node.owns(level, chunkPos.pack())) {
						// Not quiet, and not marked as remote: whatever this sets off is this
						// node's doing, and is published like any other change it makes.
						applyingIntentsOf = from;

						try {
							for (BlockUpdate update : updates) {
								level.setBlock(update.pos(), update.state(), Block.UPDATE_ALL);
							}
						} finally {
							applyingIntentsOf = Node.NO_NODE;
						}

						adoptMade(level, chunkPos, made, true);
					} else {
						applyQuietly(level, updates);
						adoptMade(level, chunkPos, made, false);
					}
				}
				default -> LOGGER.warn("Node #{} published an unknown payload {}", from, kind);
			}
		} catch (IndexOutOfBoundsException | DecoderException e) {
			LOGGER.warn("Node #{} published a malformed payload: {}", from, e.toString());
		}
	}

	private static List<BlockUpdate> readUpdates(FriendlyByteBuf in, ChunkPos chunkPos) {
		int count = in.readVarInt();
		List<BlockUpdate> updates = new ArrayList<>(Math.min(count, 4096));

		for (int i = 0; i < count; i++) {
			BlockPos pos = BlockPos.of(in.readLong());
			BlockState state = Block.stateById(in.readVarInt());

			// A payload speaks for its own chunk only.
			if (ChunkPos.pack(pos) == chunkPos.pack()) {
				updates.add(new BlockUpdate(pos, state));
			}
		}

		return updates;
	}

	private static List<CompoundTag> readMade(FriendlyByteBuf in) {
		int count = in.readVarInt();
		List<CompoundTag> made = new ArrayList<>(Math.min(count, 256));

		for (int i = 0; i < count; i++) {
			CompoundTag tag = in.readNbt();

			if (tag != null) {
				made.add(tag);
			}
		}

		return made;
	}

	/**
	 * Takes on block entities made with blocks a player placed on another node. On the owner they
	 * are then this node's own, and go out to the other replicas as changes.
	 */
	private void adoptMade(ServerLevel level, ChunkPos chunkPos, List<CompoundTag> made, boolean owned) {
		for (CompoundTag tag : made) {
			BlockPos pos = BlockEntity.getPosFromTag(chunkPos, tag);

			if (ChunkPos.pack(pos) == chunkPos.pack()) {
				node.blockEntities().adopt(level, pos, tag);
				BlockEntity blockEntity = level.getBlockEntity(pos);

				if (owned && blockEntity != null) {
					blockEntity.setChanged();
				}
			}
		}
	}

	void applyQuietly(ServerLevel level, List<BlockUpdate> updates) {
		applyingRemote = true;

		try {
			for (BlockUpdate update : updates) {
				level.setBlock(update.pos(), update.state(), QUIET);
			}
		} finally {
			applyingRemote = false;
		}
	}

	public void onChunkAvailable(ServerLevel level, ChunkPos pos) { snapshots.onChunkAvailable(level, pos); }
	public void onRegionOwner(ServerLevel level, LongCollection chunks, int owner) { snapshots.onRegionOwner(level, chunks, owner); }
	public boolean isSynced(ServerLevel level, ChunkPos pos) { return snapshots.isSynced(level, pos); }
	public void onChunkUnload(ServerLevel level, ChunkPos pos) { snapshots.onChunkUnload(level, pos); }
	public void onDirect(int from, ByteBuffer payload) { snapshots.onDirect(from, payload); }
	public void onDisconnected() { dirty.clear(); snapshots.onDisconnected(); }
}
