package dev.lodecore.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import dev.lodecore.LodecoreConfig;
import dev.lodecore.Node;
import dev.lodecore.net.Wire;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/** Central storage bridge. Network acknowledgements never wait for the game thread. */
public final class WorldStorage {
	private static final AtomicLong sequence = new AtomicLong();
	private static final Map<Long, CompletableFuture<StorageProtocol.Reply>> pending = new ConcurrentHashMap<>();
	private static final Map<Region, Boolean> owned = new ConcurrentHashMap<>();
	private static volatile StorageReader reader;
	private static volatile SaveCollector saves;
	private static volatile Node node;
	private static volatile boolean connected;
	private static volatile boolean joined;
	private static volatile boolean master;
	private static volatile boolean sealed;
	private static long bootstrapEpoch;

	private record Region(String dimension, long position) { }
	public record Loaded(boolean present, CompoundTag data) { }

	private WorldStorage() { }

	public static void initialize(LodecoreConfig config) {
		reader = new StorageReader(config);
		saves = new SaveCollector(config);
		try {
			bootstrapEpoch = ByteBuffer.wrap(reader.request(StorageProtocol.EPOCH, "cluster", "epoch", 0, 0).data()).getLong();
		} catch (IOException e) { throw new IllegalStateException("Cannot start without the authoritative world", e); }
	}
	public static long epoch() { return bootstrapEpoch; }
	public static void attach(Node attached) { node = attached; }
	public static void connected(int nodeId) {
		connected = true; joined = true; sealed = false;
		saves.connect(nodeId, bootstrapEpoch);
	}

	public static void disconnected() {
		connected = false;
		sealed = true;
		owned.clear();
		master = false;
		pending.values().forEach(future -> future.completeExceptionally(new IOException("Lost Lodestar while saving the world")));
		pending.clear();
	}

	public static void close() throws IOException {
		disconnected();
		if (saves != null) saves.close();
		saves = null;
		if (reader != null) reader.close();
		reader = null;
		node = null;
	}

	public static void owner(String dimension, int x, int z, int owner) {
		// Keep retired regions writable until Lodestar names a successor; vanilla's
		// asynchronous unload saves finish after the region subscription disappears.
		if (owner != 0) owned.put(new Region(dimension, ChunkPos.pack(x, z)), owner == node.nodeId());
	}

	public static void seal() { sealed = true; }

	public static boolean enabled() { return reader != null; }

	static boolean canSave(SaveCollector.Key key) {
		if (key.dimension().equals("cluster")) return master;
		return owned.getOrDefault(new Region(key.dimension(), Node.regionOf(ChunkPos.pack(key.x(), key.z()))), false);
	}

	static void failed(IOException error) {
		dev.lodecore.Lodecore.LOGGER.error("Asynchronous world save failed", error);
		disconnected();
		Node current = node;
		if (current != null) {
			current.server().halt(false);
			SessionFailure.stop(current.server());
		}
	}

	public static void snapshot(long epoch, long revision, int round, Map<UUID, CompoundTag> players) throws IOException {
		if (!connected || sealed) throw new IOException("Cannot capture a disconnected world");
		saves.submit(epoch, revision, round, players);
	}

	public static void master(boolean isMaster) { master = isMaster; }

	public static Loaded loadGlobal(String key) throws IOException {
		return load("cluster", "saved/" + key, 0, 0);
	}

	public static void saveGlobal(String key, CompoundTag data) throws IOException {
		if (master && !sealed) save("cluster", "saved/" + key, 0, 0, data);
	}

	public static Loaded load(RegionStorageInfo info, ChunkPos pos) throws IOException {
		return load(info.dimension().identifier().toString(), info.type(), pos.x(), pos.z());
	}

	private static Loaded load(String dimension, String kind, int x, int z) throws IOException {
		if (reader == null) return new Loaded(false, null);
		if (joined && !connected) throw new IOException("World storage session has ended");
		try {
			SaveCollector.Key key = new SaveCollector.Key(dimension, kind, x, z);
			if (connected && canSave(key)) {
				Loaded local = saves.load(key);
				if (local != null) return local;
			}
			StorageProtocol.Reply reply = connected
					? request(StorageProtocol.READ, dimension, kind, x, z, new byte[0])
					: reader.request(StorageProtocol.READ, dimension, kind, x, z);
			if (reply.status() == 1) return new Loaded(true, null);
			CompoundTag data = reply.data().length == 0 ? null : NbtIo.readCompressed(new ByteArrayInputStream(reply.data()), NbtAccounter.create(64L << 20));
			return new Loaded(true, data);
		} catch (IOException e) {
			if (node != null) node.server().halt(false);
			throw e;
		}
	}

	public static void save(RegionStorageInfo info, ChunkPos pos, CompoundTag data) throws IOException {
		Node current = node;
		if (current == null || !joined || sealed) return;
		if (!connected) throw new IOException("Cannot save a clustered world without Lodestar");
		String dimension = info.dimension().identifier().toString();
		if (!owned.getOrDefault(new Region(dimension, Node.regionOf(pos.pack())), false)) return;
		save(dimension, info.type(), pos.x(), pos.z(), data);
	}

	private static void save(String dimension, String kind, int x, int z, CompoundTag data) throws IOException {
		Node current = node;
		if (current == null || !connected) throw new IOException("World storage is disconnected");
		saves.put(new SaveCollector.Key(dimension, kind, x, z), data);
	}

	/** Orders control-stream journals and lifecycle saves; detached snapshots commit separately. */
	public static void flush() throws IOException {
		if (connected && !sealed) request(StorageProtocol.READ, "cluster", "barrier", 0, 0, new byte[0]);
	}

	private static StorageProtocol.Reply request(int operation, String dimension, String kind, int x, int z, byte[] data) throws IOException {
		long id = sequence.incrementAndGet();
		CompletableFuture<StorageProtocol.Reply> future = new CompletableFuture<>();
		pending.put(id, future);
		try {
			node.send(StorageProtocol.request(id, operation, dimension, kind, x, z, data));
			StorageProtocol.Reply reply = future.get(15, TimeUnit.SECONDS);
			reply.check();
			return reply;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted saving the world", e);
		} catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
			throw new IOException("World save was not acknowledged by Lodestar", e);
		} finally {
			pending.remove(id);
		}
	}

	public static boolean receive(Wire.Inbound message) {
		if (!(message instanceof Wire.DirectRelay relay) || relay.from() != 0 || !relay.payload().hasRemaining()) return false;
		ByteBuffer payload = relay.payload().duplicate();
		int tag = Byte.toUnsignedInt(payload.get());
		int phase = payload.hasRemaining() ? Byte.toUnsignedInt(payload.get(payload.position())) : -1;
		if (tag == 241 && payload.remaining() == 21 && (phase == 6 || phase == 7)) {
			payload.get();
			long epoch = payload.getLong();
			long revision = payload.getLong();
			if (epoch == bootstrapEpoch && saves != null) {
				if (phase == 6) saves.committed(revision);
				else try { saves.retry(revision); } catch (IOException e) { failed(e); }
			}
			return true;
		}
		if (tag != StorageProtocol.KIND) return false;
		try {
			StorageProtocol.Reply reply = StorageProtocol.reply(relay.payload());
			CompletableFuture<StorageProtocol.Reply> future = pending.get(reply.id());
			if (future != null) future.complete(reply);
		} catch (IOException e) {
			disconnected();
		}
		return true;
	}

	static byte[] journal(String scope, long offset) throws IOException {
		if (connected) return request(StorageProtocol.JOURNAL, scope, "journal", (int) (offset >>> 32), (int) offset, new byte[0]).data();
		return reader.request(StorageProtocol.JOURNAL, scope, "journal", (int) (offset >>> 32), (int) offset).data();
	}
}
