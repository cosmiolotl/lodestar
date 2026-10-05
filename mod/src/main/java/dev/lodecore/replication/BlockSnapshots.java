package dev.lodecore.replication;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import dev.lodecore.Node;
import dev.lodecore.net.Wire;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

/** Initial chunk snapshots and their synchronization/unload bookkeeping. */
final class BlockSnapshots {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/blocks");
	private static final int SNAPSHOT_REQUEST_TICKS = 60 * 20;
	private static final int SNAPSHOT_REQUEST = 3;
	private static final int SNAPSHOT = 4;
	private final Node node;
	private final BlockReplication blocks;
	/** Loaded chunks whose blocks are known to match the owner's copy, as lodestar has been told. */
	final Map<ResourceKey<Level>, LongSet> synced = new HashMap<>();
	/** Chunks a snapshot has been asked for and has not arrived yet. */
	private final Map<ResourceKey<Level>, LongSet> awaitingSnapshot = new HashMap<>();
	private final List<SnapshotRequest> snapshotRequests = new ArrayList<>();

	private record SnapshotRequest(int from, int dimension, int chunkX, int chunkZ, int expiresAt) {
	}


	BlockSnapshots(Node node, BlockReplication blocks) { this.node = node; this.blocks = blocks; }
	private static void remove(Map<ResourceKey<Level>, LongSet> sets, ServerLevel level, ChunkPos pos) {
		LongSet chunks = sets.get(level.dimension());
		if (chunks != null) chunks.remove(pos.pack());
	}
	/** Answers the snapshot requests it can. */
	public void answerSnapshotRequests() {
		int now = node.server().getTickCount();

		for (Iterator<SnapshotRequest> requests = snapshotRequests.iterator(); requests.hasNext(); ) {
			SnapshotRequest request = requests.next();

			if (trySendSnapshot(request) || now > request.expiresAt()) {
				requests.remove();
			}
		}
	}

	/**
	 * A chunk has been loaded, or has become usable again after nearly being unloaded. On the
	 * owner it is the truth; on a replica, a chunk not known to match the owner's copy catches up
	 * with it.
	 */
	public void onChunkAvailable(ServerLevel level, ChunkPos chunkPos) {
		ResourceKey<Level> dimension = level.dimension();
		long chunk = chunkPos.pack();
		int owner = node.ownerOf(dimension, chunk);

		if (owner == node.nodeId()) {
			markSynced(level, chunk);
		} else if (owner != Node.NO_NODE
				&& !synced.getOrDefault(dimension, LongSet.of()).contains(chunk)
				&& !awaitingSnapshot.getOrDefault(dimension, LongSet.of()).contains(chunk)) {
			requestSnapshot(level, chunk, owner);
		}
	}

	/**
	 * lodestar named the owner of a region, for the first time, or because it was handed over or
	 * its old owner left. {@code chunks} are the region's chunks this node has loaded. What this
	 * node has of the region is either now the truth, or has to catch up with the new owner's.
	 */
	public void onRegionOwner(ServerLevel level, LongCollection chunks, int owner) {
		LongSet syncedChunks = synced.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet());
		LongSet awaiting = awaitingSnapshot.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet());

		for (long chunk : chunks) {
			// A snapshot still on its way comes from the old owner, and is ignored when it arrives.
			awaiting.remove(chunk);

			if (owner == node.nodeId()) {
				markSynced(level, chunk);
			} else if (owner != Node.NO_NODE && !syncedChunks.contains(chunk)) {
				// A chunk that matched the old owner's copy matches the new owner's too: the new
				// owner only takes over once its copy matches.
				requestSnapshot(level, chunk, owner);
			}
		}
	}

	/** Whether this node's copy of a chunk is known to match its owner's, or is the owner's. */
	public boolean isSynced(ServerLevel level, ChunkPos chunk) {
		return synced.getOrDefault(level.dimension(), LongSet.of()).contains(chunk.pack());
	}

	private void markSynced(ServerLevel level, long chunk) {
		int dimension = node.dimensionId(level.dimension());

		if (synced.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet()).add(chunk) && dimension >= 0) {
			node.send(Wire.synced(dimension, ChunkPos.getX(chunk), ChunkPos.getZ(chunk)));
		}
	}

	private void requestSnapshot(ServerLevel level, long chunk, int owner) {
		awaitingSnapshot.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet()).add(chunk);
		FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer(16));
		out.writeByte(SNAPSHOT_REQUEST);
		out.writeVarInt(node.dimensionId(level.dimension()));
		out.writeInt(ChunkPos.getX(chunk));
		out.writeInt(ChunkPos.getZ(chunk));
		node.send(Wire.direct(owner, ByteBufUtil.getBytes(out)));
	}

	public void onChunkUnload(ServerLevel level, ChunkPos chunkPos) {
		remove(synced, level, chunkPos);
		remove(awaitingSnapshot, level, chunkPos);
	}

	public void onDisconnected() {
		synced.clear();
		awaitingSnapshot.clear();
		snapshotRequests.clear();
	}

	/** Another node sent something to this node alone. */
	public void onDirect(int from, ByteBuffer payload) {
		try {
			RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload), node.server().registryAccess());
			int kind = in.readByte();
			int dimension = in.readVarInt();
			int chunkX = in.readInt();
			int chunkZ = in.readInt();

			switch (kind) {
				case SNAPSHOT_REQUEST -> {
					int expiresAt = node.server().getTickCount() + SNAPSHOT_REQUEST_TICKS;
					snapshotRequests.add(new SnapshotRequest(from, dimension, chunkX, chunkZ, expiresAt));
				}
				case SNAPSHOT -> {
					ServerLevel level = node.level(dimension);

					if (level != null) {
						applySnapshot(from, level, new ChunkPos(chunkX, chunkZ), in);
					}
				}
				default -> LOGGER.warn("Node #{} sent an unknown payload {}", from, kind);
			}
		} catch (IndexOutOfBoundsException | DecoderException e) {
			LOGGER.warn("Node #{} sent a malformed payload: {}", from, e.toString());
		}
	}

	/**
	 * Answers a snapshot request if the chunk is loaded here. The requester loading the chunk is
	 * what makes this node load it, so a request can arrive before the chunk does.
	 *
	 * @return whether the request is dealt with
	 */
	private boolean trySendSnapshot(SnapshotRequest request) {
		ServerLevel level = node.level(request.dimension());

		// If this node no longer owns the region, the requester hears of the new owner and asks it.
		if (level == null || !node.owns(level, ChunkPos.pack(request.chunkX(), request.chunkZ()))) {
			return true;
		}

		LevelChunk chunk = level.getChunkSource().getChunkNow(request.chunkX(), request.chunkZ());

		if (chunk == null) {
			return false;
		}

		LevelChunkSection[] sections = chunk.getSections();
		RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
		out.writeByte(SNAPSHOT);
		out.writeVarInt(request.dimension());
		out.writeInt(request.chunkX());
		out.writeInt(request.chunkZ());
		out.writeVarInt(sections.length);

		for (LevelChunkSection section : sections) {
			section.getStates().write(out);
		}

		node.blockEntities().writeSnapshot(level, chunk, out);
		node.entities().writeSnapshot(level, chunk.getPos(), out);
		node.send(Wire.direct(request.from(), ByteBufUtil.getBytes(out)));
		return true;
	}

	private void applySnapshot(int from, ServerLevel level, ChunkPos chunkPos, RegistryFriendlyByteBuf in) {
		LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());

		if (chunk == null || from != node.ownerOf(level.dimension(), chunkPos.pack())) {
			return;
		}

		LevelChunkSection[] sections = chunk.getSections();

		if (in.readVarInt() != sections.length) {
			LOGGER.warn("Node #{} sent a snapshot of {} with a different world height", from, chunkPos);
			return;
		}

		// Nodes generate the same terrain from the same seed, so a snapshot usually differs from
		// the local copy in a few blocks at most. Adopting just those goes through the ordinary
		// block update path, which looks after lighting, heightmaps and clients.
		List<BlockReplication.BlockUpdate> differences = new ArrayList<>();

		for (int index = 0; index < sections.length; index++) {
			PalettedContainer<BlockState> theirs = level.palettedContainerFactory().createForBlockStates();
			theirs.read(in);
			LevelChunkSection ours = sections[index];
			int minY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));

			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = theirs.get(x, y, z);

						if (state != ours.getBlockState(x, y, z)) {
							differences.add(new BlockReplication.BlockUpdate(new BlockPos(chunkPos.getBlockX(x), minY + y, chunkPos.getBlockZ(z)), state));
						}
					}
				}
			}
		}

		blocks.applyQuietly(level, differences);
		node.blockEntities().applySnapshot(level, chunkPos, in);
		node.entities().applySnapshot(level, chunkPos, in);
		remove(awaitingSnapshot, level, chunkPos);
		markSynced(level, chunkPos.pack());
		LOGGER.debug("Adopted {} blocks of {} from node #{}", differences.size(), chunkPos, from);
	}
}
