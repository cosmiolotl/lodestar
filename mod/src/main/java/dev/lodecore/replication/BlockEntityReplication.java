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
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueInput;

/**
 * Keeps block entities (what is in a chest or a furnace, the text on a sign, the book on a
 * lectern) the same on every node that has them loaded.
 *
 * <p>A block entity's authority is the owner of its region, or the node that has custody of it
 * while one of its players uses it (see {@link Custody}). At the end of every tick, the authority
 * publishes the whole of each block entity that changed to the block entity's chunk, and every
 * other node adopts it. Snapshots of a chunk carry its block entities too.
 *
 * <p>A block entity can also be made on a node that is not its authority: a player there places
 * a block that has one, such as a shulker box with things in it. The block itself goes to the
 * owner as an intent, and the block entity, as the player's node made it, goes with it, so that
 * the owner's copy has what the player's item had.
 *
 * <p>Everything here runs on the game thread.
 */
public final class BlockEntityReplication {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/block-entities");

	/** Payload published to a chunk's subscribers: the block entities in it that changed. */
	public static final int BLOCK_ENTITIES = 9;

	private final Node node;
	/** Set while applying what another node sent, so that it is not sent back out. */
	private boolean applyingRemote;
	/** Block entities this node is the authority for that changed this tick, by level. */
	private final Map<ResourceKey<Level>, LongSet> changed = new HashMap<>();
	/** Block entities a player here made this tick, in regions owned elsewhere, by level. */
	private final Map<ResourceKey<Level>, LongSet> made = new HashMap<>();

	public BlockEntityReplication(Node node) {
		this.node = node;
	}

	/** Whether this node's copy of the block entity at a position is the truth. */
	public boolean isAuthority(ServerLevel level, BlockPos pos) {
		if (!node.isConnected()) {
			return true;
		}

		int holder = node.custody().holderOf(level, pos);
		return holder != Node.NO_NODE ? holder == node.nodeId() : node.owns(level, ChunkPos.pack(pos));
	}

	/** The node whose copy of a block entity is the truth, or {@link Node#NO_NODE} if unknown. */
	private int authorityOf(ServerLevel level, BlockPos pos) {
		int holder = node.custody().holderOf(level, pos);
		return holder != Node.NO_NODE ? holder : node.ownerOf(level.dimension(), ChunkPos.pack(pos));
	}

	/** Called whenever a block entity says it changed. */
	void onChanged(BlockEntity blockEntity) {
		if (!node.isConnected() || applyingRemote || !(blockEntity.getLevel() instanceof ServerLevel level)) {
			return;
		}

		if (isAuthority(level, blockEntity.getBlockPos())) {
			changed.computeIfAbsent(level.dimension(), key -> new LongLinkedOpenHashSet()).add(blockEntity.getBlockPos().asLong());
		}
	}

	/** Called whenever a block entity is put in a chunk, which is how a placed block gets one. */
	void onMade(ServerLevel level, BlockEntity blockEntity) {
		if (node.isConnected() && !applyingRemote && !node.blocks().isApplyingRemote() && !isAuthority(level, blockEntity.getBlockPos())) {
			made.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet()).add(blockEntity.getBlockPos().asLong());
		}
	}

	/**
	 * The block entity a player here made this tick at a position, as it is now, to go to the
	 * owner with the block. Null if there is none.
	 */
	@Nullable CompoundTag takeMade(ServerLevel level, BlockPos pos) {
		LongSet positions = made.get(level.dimension());

		if (positions == null || !positions.remove(pos.asLong())) {
			return null;
		}

		BlockEntity blockEntity = level.getBlockEntity(pos);
		return blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess());
	}

	/** Publishes the block entities that changed this tick. */
	public void flush() {
		// Whatever was made and not taken along with a block was not placed by a player.
		made.clear();

		for (Map.Entry<ResourceKey<Level>, LongSet> entry : changed.entrySet()) {
			ServerLevel level = node.server().getLevel(entry.getKey());
			int dimension = node.dimensionId(entry.getKey());

			if (level == null || dimension < 0) {
				continue;
			}

			Long2ObjectMap<List<CompoundTag>> byChunk = new Long2ObjectOpenHashMap<>();

			for (long packed : entry.getValue()) {
				BlockPos pos = BlockPos.of(packed);
				long chunk = ChunkPos.pack(pos);
				BlockEntity blockEntity = level.getBlockEntity(pos);

				// Nobody else has a copy of a block entity in a chunk nobody else has loaded.
				if (blockEntity == null || !isAuthority(level, pos) || node.owns(level, chunk) && !node.isNeededElsewhere(level, chunk)) {
					continue;
				}

				byChunk.computeIfAbsent(chunk, key -> new ArrayList<>()).add(blockEntity.saveWithFullMetadata(level.registryAccess()));
			}

			for (Long2ObjectMap.Entry<List<CompoundTag>> chunk : byChunk.long2ObjectEntrySet()) {
				FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
				out.writeByte(BLOCK_ENTITIES);
				write(out, chunk.getValue());
				long pos = chunk.getLongKey();
				node.send(Wire.publish(dimension, ChunkPos.getX(pos), ChunkPos.getZ(pos), ByteBufUtil.getBytes(out)));
			}

			entry.getValue().clear();
		}
	}

	private static void write(FriendlyByteBuf out, List<CompoundTag> blockEntities) {
		out.writeVarInt(blockEntities.size());

		for (CompoundTag tag : blockEntities) {
			out.writeNbt(tag);
		}
	}

	/** The authority of some block entities in a chunk published them. */
	public void onRelay(int from, ServerLevel level, ChunkPos chunkPos, ByteBuffer payload) {
		try {
			FriendlyByteBuf in = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
			in.readByte();
			int count = in.readVarInt();

			for (int i = 0; i < count; i++) {
				CompoundTag tag = in.readNbt();
				BlockPos pos = tag == null ? null : BlockEntity.getPosFromTag(chunkPos, tag);

				// A payload speaks for its own chunk only, and only for what its sender is the authority for.
				if (pos != null && ChunkPos.pack(pos) == chunkPos.pack() && from == authorityOf(level, pos) && !isAuthority(level, pos)) {
					load(level, pos, tag);
				}
			}
		} catch (IndexOutOfBoundsException | DecoderException e) {
			LOGGER.warn("Node #{} published malformed block entities: {}", from, e.toString());
		}
	}

	/** Takes over the state of a block entity, whoever is the authority for it now. */
	void adopt(ServerLevel level, BlockPos pos, CompoundTag tag) {
		load(level, pos, tag);
	}

	private void load(ServerLevel level, BlockPos pos, CompoundTag tag) {
		BlockEntity blockEntity = level.getBlockEntity(pos);

		// The block may have changed meanwhile; data for another kind of block entity is not ours.
		if (blockEntity == null || !tag.getStringOr("id", "").equals(String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType())))) {
			return;
		}

		applyingRemote = true;

		try {
			blockEntity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
			level.blockEntityChanged(pos);
			// Signs, banners, skulls and the like show their data to the players that can see them.
			level.sendBlockUpdated(pos, blockEntity.getBlockState(), blockEntity.getBlockState(), Block.UPDATE_CLIENTS);

			// Comparators read containers; on the owner, they have to hear that this one changed.
			if (node.owns(level, ChunkPos.pack(pos))) {
				level.updateNeighbourForOutputSignal(pos, blockEntity.getBlockState().getBlock());
			}
		} catch (RuntimeException e) {
			LOGGER.warn("Could not adopt the block entity at {}: {}", pos, e.toString());
		} finally {
			applyingRemote = false;
		}
	}

	/** Writes every block entity in a chunk, for a snapshot. */
	public void writeSnapshot(ServerLevel level, LevelChunk chunk, FriendlyByteBuf out) {
		List<CompoundTag> blockEntities = new ArrayList<>();

		for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
			blockEntities.add(blockEntity.saveWithFullMetadata(level.registryAccess()));
		}

		write(out, blockEntities);
	}

	public void applySnapshot(ServerLevel level, ChunkPos chunkPos, FriendlyByteBuf in) {
		int count = in.readVarInt();

		for (int i = 0; i < count; i++) {
			CompoundTag tag = in.readNbt();
			BlockPos pos = tag == null ? null : BlockEntity.getPosFromTag(chunkPos, tag);

			if (pos != null && ChunkPos.pack(pos) == chunkPos.pack() && !isAuthority(level, pos)) {
				load(level, pos, tag);
			}
		}
	}

	public void onDisconnected() {
		changed.clear();
		made.clear();
	}
}
