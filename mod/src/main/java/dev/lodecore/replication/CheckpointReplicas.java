package dev.lodecore.replication;

import dev.lodecore.Node;
import dev.lodecore.net.Wire;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/** Full custody state, including container entity inventories omitted by movement deltas. */
public final class CheckpointReplicas {
	public static final int TAG = 242;
	private final Node node;
	private final Map<Key, CompoundTag> sent = new HashMap<>();
	private record Key(String dimension, Object object) { }

	public CheckpointReplicas(Node node) { this.node = node; }
	public void reset() { sent.clear(); }


	public void entities(Collection<Entity> entities) {
		for (var entity : entities) {
			if (entity.isRemoved() || !(entity.level() instanceof ServerLevel level)) continue;
			if (node.dimensionId(level.dimension()) < 0 || entity instanceof Player || !node.entities().isAuthority(level, entity)) continue;
			if (node.owns(level, entity.chunkPosition().pack()) && !node.isNeededElsewhere(level, entity.chunkPosition().pack())) continue;
			CompoundTag tag = node.entities().saveWhole(level, entity);
			if (tag == null || !changed(level, entity.getUUID(), tag)) continue;
			FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
			out.writeByte(TAG).writeByte(0);
			out.writeUUID(entity.getUUID());
			out.writeNbt(tag);
			publish(level, entity.chunkPosition(), out);
		}
	}

	public void blockEntities(ServerLevel level, LevelChunk chunk) {
		if (node.owns(level, chunk.getPos().pack()) && !node.isNeededElsewhere(level, chunk.getPos().pack())) return;
		for (var blockEntity : chunk.getBlockEntities().values()) {
			BlockPos pos = blockEntity.getBlockPos();
			if (!node.blockEntities().isAuthority(level, pos)) continue;
			CompoundTag tag = blockEntity.saveWithFullMetadata(level.registryAccess());
			if (!changed(level, pos, tag)) continue;
			FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
			out.writeByte(TAG).writeByte(1);
			out.writeBlockPos(pos);
			out.writeNbt(tag);
			publish(level, chunk.getPos(), out);
		}
	}

	private boolean changed(ServerLevel level, Object object, CompoundTag tag) {
		return !tag.equals(sent.put(new Key(level.dimension().identifier().toString(), object), tag));
	}

	private void publish(ServerLevel level, ChunkPos pos, FriendlyByteBuf out) {
		try { node.send(Wire.publish(node.dimensionId(level.dimension()), pos.x(), pos.z(), ByteBufUtil.getBytes(out))); }
		finally { out.release(); }
	}

	public static void apply(Node node, int from, ServerLevel level, ChunkPos chunk, ByteBuffer payload) {
		FriendlyByteBuf in = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
		try {
			in.readByte();
			int kind = in.readUnsignedByte();
			if (kind == 0) {
				UUID uuid = in.readUUID();
				CompoundTag tag = in.readNbt();
				int holder = node.custody().holderOf(level, uuid);
				int authority = holder == 0 ? node.ownerOf(level.dimension(), chunk.pack()) : holder;
				if (from == authority && from != node.nodeId() && tag != null) node.entities().adopt(level, uuid, tag);
			} else if (kind == 1) {
				BlockPos pos = in.readBlockPos();
				CompoundTag tag = in.readNbt();
				int holder = node.custody().holderOf(level, pos);
				int authority = holder == 0 ? node.ownerOf(level.dimension(), chunk.pack()) : holder;
				if (from == authority && from != node.nodeId() && ChunkPos.pack(pos) == chunk.pack() && tag != null) {
					node.blockEntities().adopt(level, pos, tag);
				}
			} else throw new IllegalStateException("Invalid checkpoint replica record");
		} finally { in.release(); }
	}
}
