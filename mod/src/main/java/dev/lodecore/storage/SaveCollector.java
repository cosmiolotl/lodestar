package dev.lodecore.storage;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.lodecore.LodecoreConfig;
import net.minecraft.nbt.CompoundTag;

/** Owns detached NBT snapshots; replacing a record coalesces all its changes before the next cut. */
final class SaveCollector implements AutoCloseable {

	private static final long MAX_BYTES = 256L << 20;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(
			Thread.ofPlatform().daemon(true).name("lodecore-save").factory());
	private final StorageWriter writer;
	private Map<Key, Snapshot> pending = new HashMap<>();
	private Map<Key, Snapshot> uploading = Map.of();
	private long pendingBytes;
	private long uploadingBytes;
	private long revision;
	private volatile IOException failure;

	record Key(String dimension, String kind, int x, int z) { }
	record Snapshot(CompoundTag data, long bytes) { }

	SaveCollector(LodecoreConfig config) { writer = new StorageWriter(config); }

	void connect(int node, long epoch) {
		executor.execute(() -> {
			try { writer.connect(node, epoch); }
			catch (IOException e) { fail(e); }
		});
	}

	synchronized void put(Key key, CompoundTag data) throws IOException {
		putSnapshot(key, new Snapshot(data, size(data)));
	}

	private void putSnapshot(Key key, Snapshot snapshot) throws IOException {
		check();
		Snapshot previous = pending.put(key, snapshot);
		pendingBytes += snapshot.bytes() - (previous == null ? 0 : previous.bytes());
		if (pendingBytes + uploadingBytes > MAX_BYTES) {
			IOException error = new IOException("World snapshot backlog exceeds 256 MiB");
			fail(error);
			throw error;
		}
	}

	private static long size(CompoundTag data) { return 256L + (data == null ? 0 : data.sizeInBytes()); }

	synchronized WorldStorage.Loaded load(Key key) throws IOException {
		check();
		Snapshot snapshot = pending.get(key);
		if (snapshot == null) snapshot = uploading.get(key);
		if (snapshot == null) return null;
		CompoundTag data = snapshot.data();
		return new WorldStorage.Loaded(true, data == null ? null : data.copy());
	}

	synchronized void submit(long epoch, long nextRevision, int round, Map<UUID, CompoundTag> players) throws IOException {
		check();
		if (revision != 0) throw new IOException("Previous world snapshot has not committed");
		pending.entrySet().removeIf(entry -> {
			if (WorldStorage.canSave(entry.getKey())) return false;
			pendingBytes -= entry.getValue().bytes();
			return true;
		});
		for (var player : players.entrySet()) {
			String uuid = player.getKey().toString().replace("-", "");
			put(new Key("players", uuid, 0, 0), player.getValue());
		}
		Map<Key, Snapshot> changes = pending;
		pending = new HashMap<>();
		uploading = changes;
		uploadingBytes = pendingBytes;
		pendingBytes = 0;
		revision = nextRevision;
		executor.execute(() -> {
			try {
				check();
				writer.upload(epoch, nextRevision, round, changes);
			} catch (IOException e) { fail(e); }
		});
	}

	synchronized void committed(long committedRevision) {
		if (committedRevision != revision) return;
		uploading = Map.of();
		uploadingBytes = 0;
		revision = 0;
	}

	synchronized void retry(long retriedRevision) throws IOException {
		if (retriedRevision != revision) return;
		Map<Key, Snapshot> previous = uploading;
		committed(retriedRevision);
		for (var record : previous.entrySet()) {
			if (!pending.containsKey(record.getKey())) putSnapshot(record.getKey(), record.getValue());
		}
	}

	private void check() throws IOException { if (failure != null) throw failure; }

	private void fail(IOException error) {
		failure = error;
		try { writer.close(); } catch (IOException ignored) { }
		WorldStorage.failed(error);
	}

	@Override
	public void close() throws IOException {
		executor.shutdownNow();
		writer.close();
	}
}
