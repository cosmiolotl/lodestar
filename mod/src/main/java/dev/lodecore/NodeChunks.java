package dev.lodecore;

import dev.lodecore.net.Wire;
import dev.lodecore.replication.TickRange;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/** Chunk membership and player locality reports. */
final class NodeChunks {
	private final Node node;
	NodeChunks(Node node) { this.node = node; }
	void onChunkLoad(ServerLevel level, ChunkPos pos) {
		if (node.loadedChunks.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet()).add(pos.pack())) {
			int dimension = node.dimensionId(level.dimension());

			if (dimension != Node.NOT_RESOLVED) {
				node.send(Wire.subscribe(dimension, pos.x(), pos.z()));
				node.blocks.onChunkAvailable(level, pos);
				node.persistence.loaded(level, pos.pack());
			}
		}
	}

	/** A loaded chunk became usable again, possibly after missing changes while it was not. */
	void onChunkAccessible(ServerLevel level, ChunkPos pos) {
		if (node.connected && node.loadedChunks.getOrDefault(level.dimension(), LongSet.of()).contains(pos.pack())) {
			node.blocks.onChunkAvailable(level, pos);
			node.persistence.loaded(level, pos.pack());
		}
	}

	void onChunkUnload(ServerLevel level, ChunkPos pos) {
		LongSet chunks = node.loadedChunks.get(level.dimension());

		if (chunks != null && chunks.remove(pos.pack())) {
			// Unsubscribing below withdraws the report on lodestar's side.
			LongSet reported = node.reportedTicking.get(level.dimension());
			if (reported != null) reported.remove(pos.pack());
			node.blocks.onChunkUnload(level, pos);
			int dimension = node.dimensionId(level.dimension());

			if (dimension != Node.NOT_RESOLVED) {
				node.send(Wire.unsubscribe(dimension, pos.x(), pos.z()));
			}
		}
	}

	/** Tells lodestar where the players homed here are, which is what it hands out regions by. */
	void reportPlayers() {
		for (ServerPlayer player : node.server.getPlayerList().getPlayers()) {
			int dimension = node.dimensionId(player.level().dimension());

			if (dimension == Node.NOT_RESOLVED) {
				continue;
			}

			ChunkPos chunk = player.chunkPosition();
			Node.Location at = new Node.Location(dimension, chunk.pack());

			if (!at.equals(node.reportedPlayers.put(player.getUUID(), at))) {
				node.send(Wire.playerAt(player.getUUID(), dimension, chunk.x(), chunk.z()));
			}
		}
	}

	/**
	 * Tells lodestar which loaded chunks this node would tick if it owned their region, which is
	 * what the owner is then asked to tick on its behalf.
	 */
	void reportTickingChunks() {
		for (ServerLevel level : node.server.getAllLevels()) {
			ResourceKey<Level> dimension = level.dimension();
			int dimensionId = node.dimensionId(dimension);
			LongSet chunks = node.loadedChunks.get(dimension);

			if (dimensionId == Node.NOT_RESOLVED || chunks == null) {
				continue;
			}

			LongSet reported = node.reportedTicking.computeIfAbsent(dimension, key -> new LongOpenHashSet());

			for (long chunk : chunks) {
				boolean ticking = ((TickRange) level).lodecore$inTickRange(chunk);

				if (ticking ? reported.add(chunk) : reported.remove(chunk)) {
					node.send(Wire.setTicking(dimensionId, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), ticking));
				}
			}
		}
	}

}
