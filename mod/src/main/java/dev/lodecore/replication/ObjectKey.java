package dev.lodecore.replication;

import java.nio.ByteBuffer;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/**
 * Names an object that custody can be claimed of: an entity, by UUID, or a block entity, by
 * position. lodestar sees only the bytes.
 */
public record ObjectKey(@Nullable UUID entity, long block) {
	private static final byte ENTITY = 1;
	private static final byte BLOCK = 2;

	public static ObjectKey of(Entity entity) {
		return new ObjectKey(entity.getUUID(), 0);
	}

	public static ObjectKey of(BlockPos pos) {
		return new ObjectKey(null, pos.asLong());
	}

	public boolean isEntity() {
		return entity != null;
	}

	public BlockPos blockPos() {
		return BlockPos.of(block);
	}

	public byte[] toBytes() {
		ByteBuffer out = ByteBuffer.allocate(isEntity() ? 17 : 9);

		if (entity != null) {
			out.put(ENTITY).putLong(entity.getMostSignificantBits()).putLong(entity.getLeastSignificantBits());
		} else {
			out.put(BLOCK).putLong(block);
		}

		return out.array();
	}

	/** @throws IllegalArgumentException if the bytes name nothing */
	public static ObjectKey fromBytes(ByteBuffer bytes) {
		ByteBuffer in = bytes.duplicate();

		try {
			ObjectKey key = switch (in.get()) {
				case ENTITY -> new ObjectKey(new UUID(in.getLong(), in.getLong()), 0);
				case BLOCK -> new ObjectKey(null, in.getLong());
				default -> throw new IllegalArgumentException("unknown object kind");
			};

			if (in.hasRemaining()) {
				throw new IllegalArgumentException("trailing bytes after object");
			}

			return key;
		} catch (java.nio.BufferUnderflowException e) {
			throw new IllegalArgumentException("object is truncated");
		}
	}
}
