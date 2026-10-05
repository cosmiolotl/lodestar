package dev.lodecore.storage;

import dev.lodecore.Node;
import dev.lodecore.mixin.EntityStorageStateAccessor;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

/** Bounded periodic checkpoints and a final checkpoint before withdrawing a chunk. */
public final class ChunkPersistence {
	/**
	 * Skips the background saves, for measuring what they cost. Not for real worlds: chunks then
	 * reach central storage only at checkpoints and when they unload.
	 */
	private static final boolean SKIP_BACKGROUND_SAVES = Boolean.getBoolean("lodecore.experimental.skipBackgroundSaves");
	private final Node node;
	private final Queue<Pending> queue = new ArrayDeque<>();
	private final Set<Pending> queued = new HashSet<>();
	private record Pending(ServerLevel level, long chunk) { }

	public ChunkPersistence(Node node) { this.node = node; }

	private final Set<Pending> dirtyChunks = ConcurrentHashMap.newKeySet();
	private final Set<Pending> replicaChunks = ConcurrentHashMap.newKeySet();
	private final Set<Entity> replicaEntities = Collections.newSetFromMap(new IdentityHashMap<>());

	public void changed(ServerLevel level, long chunk) {
		Pending key = new Pending(level, chunk);
		dirtyChunks.add(key);
		replicaChunks.add(key);
	}

	public void entityChanged(Entity entity) {
		if (!(entity.level() instanceof ServerLevel level)) return;
		var manager = ((EntityStorageStateAccessor) level).lodecore$entityManager();
		if (!manager.isLoaded(entity.getUUID())) return;
		((DirtyEntityStorage) manager).lodecore$dirty(entity.chunkPosition().pack());
		// Vanilla saves passengers inside their root vehicle, which can straddle a chunk edge.
		Entity root = entity.getRootVehicle();
		if (root != entity) ((DirtyEntityStorage) manager).lodecore$dirty(root.chunkPosition().pack());
		if (!entity.isRemoved() && level.getEntity(entity.getUUID()) == entity) replicaEntities.add(entity);
	}

	public Set<Entity> takeReplicaEntities() {
		Set<Entity> result = new HashSet<>(replicaEntities);
		replicaEntities.clear();
		return result;
	}

	public void replicaChunks(BiConsumer<ServerLevel, LevelChunk> action) {
		visit(replicaChunks, action);
	}

	public void captureChunks() { visit(dirtyChunks, this::save); }

	private void visit(Set<Pending> pending, BiConsumer<ServerLevel, LevelChunk> action) {
		for (Pending entry : pending.toArray(Pending[]::new)) {
			pending.remove(entry);
			LevelChunk chunk = entry.level().getChunkSource().getChunkNow(ChunkPos.getX(entry.chunk()), ChunkPos.getZ(entry.chunk()));
			if (chunk != null) action.accept(entry.level(), chunk);
		}
	}

	public void prepareEntities() {
		for (ServerLevel level : node.server().getAllLevels()) {
			((DirtyEntityStorage) ((EntityStorageStateAccessor) level).lodecore$entityManager()).lodecore$capture();
		}
	}

	public void loaded(ServerLevel level, long chunk) {
		changed(level, chunk);
		Pending pending = new Pending(level, chunk);
		if (queued.add(pending)) queue.add(pending);
		LevelChunk loaded = level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
		if (loaded != null) loaded.markUnsaved();
	}

	public void tick() {
		if (SKIP_BACKGROUND_SAVES) {
			queue.clear();
			queued.clear();
			return;
		}
		for (int count = 0; count < 2 && !queue.isEmpty(); count++) {
			Pending next = queue.remove();
			queued.remove(next);
			LevelChunk chunk = next.level().getChunkSource().getChunkNow(ChunkPos.getX(next.chunk()), ChunkPos.getZ(next.chunk()));
			if (chunk != null && node.owns(next.level(), next.chunk())) save(next.level(), chunk);
		}
	}

	public void save(ServerLevel level, LevelChunk chunk) {
		if (!node.isConnected() || !node.owns(level, chunk.getPos().pack())) return;
		if (!chunk.tryMarkSaved()) return;
		// Vanilla's ordered queue also contains unload/autosave snapshots. Its write only
		// stages an immutable tag; compression and network I/O belong to the save executor.
		var data = SerializableChunkData.copyOf(level, chunk);
		level.getChunkSource().chunkMap.write(chunk.getPos(), data::write).exceptionally(error -> {
			WorldStorage.failed(new IOException("Could not capture chunk " + chunk.getPos(), error));
			return null;
		});
	}
}
