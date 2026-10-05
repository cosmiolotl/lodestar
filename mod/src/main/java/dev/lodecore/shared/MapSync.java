package dev.lodecore.shared;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import dev.lodecore.Node;
import dev.lodecore.mixin.MapIndexAccessor;
import dev.lodecore.mixin.MapItemSavedDataAccessor;
import dev.lodecore.net.Wire;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.saveddata.maps.MapBanner;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapFrame;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapIndex;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * Filled maps, kept the same on every node, and their ids, which no two nodes hand out twice.
 *
 * <p>A map's id comes from blocks of map ids lodestar hands each node, rather than from the
 * server's own count, so that two nodes never make different maps with the same id.
 *
 * <p>A map is drawn on the node of the player who carries it, so any node can change a map. Like
 * the rest of the cluster's data, maps are kept by the master of the cluster's state: a node sends
 * the master what it drew at the end of the tick, the pixels that changed and the map's banners and
 * frames, and the master takes that on and broadcasts it as its own change. A new map goes to the
 * master whole. Every node takes on what the master broadcasts for the maps it has.
 *
 * <p>There are many maps, so a node is not sent all of them when it joins. It asks the master for
 * a map the first time it uses it, and takes on the master's copy in place of its own. If the
 * master has none, the node's own copy becomes the master's.
 *
 * <p>Everything here runs on the game thread.
 */
public final class MapSync {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/maps");

	// Payloads, broadcast by the master or sent to one node.
	/** Maps, or changes to them, from the master. */
	private static final int MAP_STATE = 16;
	/** Asks the master for maps, whole. */
	private static final int MAP_REQUEST = 17;
	/** Changes to maps made on a node other than the master, sent to the master. */
	private static final int MAP_CHANGE = 18;

	// Records in a payload, each about one map.
	/** All of a map. */
	private static final int FULL = 1;
	/** The pixels of a map that changed, and its banners and frames. */
	private static final int PATCH = 2;
	/** The master has no such map. */
	private static final int NONE = 3;

	private static final int SIZE = 128;
	/** Payloads are cut at about this size, well under the largest frame lodestar takes. */
	private static final int PAYLOAD_SIZE = 1 << 20;
	private static final Codec<List<MapBanner>> BANNERS = MapBanner.CODEC.listOf();
	private static final Codec<List<MapFrame>> FRAMES = MapFrame.CODEC.listOf();

	/** What changed of a map here since the end of the last tick. */
	private static final class Dirty {
		boolean full;
		boolean markers;
		int minX = SIZE;
		int minY = SIZE;
		int maxX = -1;
		int maxY = -1;

		boolean hasPixels() {
			return maxX >= 0;
		}
	}

	private final Node node;
	private final CounterBlocks ids = new CounterBlocks(Wire.COUNTER_MAP_IDS);
	/** The id of each map this node has seen, by the map. */
	private final Map<MapItemSavedData, Integer> known = new WeakHashMap<>();
	private final Int2ObjectMap<Dirty> dirty = new Int2ObjectLinkedOpenHashMap<>();
	/** The maps this node has taken from the master since it last changed. */
	private final IntSet fresh = new IntOpenHashSet();
	/** Maps to ask the master for at the end of the tick, and those asked for and not answered. */
	private final IntSet wanted = new IntOpenHashSet();
	private final IntSet requested = new IntOpenHashSet();
	/** The maps each node asked this node, as master, for. */
	private final Int2ObjectMap<IntList> requests = new Int2ObjectOpenHashMap<>();
	private int master = Node.NO_NODE;
	/** Set while taking on another node's maps, so that they are not taken for changes made here. */
	private boolean applying;

	public MapSync(Node node) {
		this.node = node;
	}

	public static boolean handles(int kind) {
		return kind == MAP_STATE || kind == MAP_REQUEST || kind == MAP_CHANGE;
	}

	private boolean isMaster() {
		return !node.isConnected() || master == node.nodeId();
	}

	/** Whether a change made now is to be passed on, as {@link SharedData#recording()}. */
	private boolean recording() {
		return node.isConnected() && (!applying || master == node.nodeId());
	}

	private SavedDataStorage storage() {
		return node.server().getDataStorage();
	}

	private static SavedDataType<MapItemSavedData> type(int id) {
		return MapItemSavedData.type(new MapId(id));
	}

	private Dirty dirty(int id) {
		return dirty.computeIfAbsent(id, key -> new Dirty());
	}

	public void onMaster(int master) {
		if (this.master != master) {
			// Another master's copies may differ.
			fresh.clear();
			requested.clear();
		}

		this.master = master;
	}

	// ---- map ids ----

	/**
	 * The id for a new map, from this node's own blocks, or null to take the server's own next id:
	 * cut off from lodestar, or before lodestar has handed this node a block.
	 */
	public @Nullable MapId nextId() {
		if (!node.isConnected()) {
			return null;
		}

		MapIndex index = storage().computeIfAbsent(MapIndex.TYPE);
		MapIndexAccessor accessor = (MapIndexAccessor) index;
		int id = ids.next(accessor.lodecore$lastMapId() + 1);

		if (id < 0) {
			LOGGER.warn("This node has no map ids of its own yet; the next is the server's own");
			return null;
		}

		// The server's own count carries on past it, should this node be cut off.
		accessor.lodecore$setLastMapId(id);
		index.setDirty();
		return new MapId(id);
	}

	public void onIdBlock(int block) {
		ids.onBlock(block);
	}

	// ---- changes made here ----

	/** The game looked up a map. Called often: for every map every player carries, every tick. */
	public void onLookup(MapId id, @Nullable MapItemSavedData map) {
		if (map != null) {
			known.putIfAbsent(map, id.id());
		}

		if (node.isConnected() && master != Node.NO_NODE && !isMaster() && !fresh.contains(id.id()) && !requested.contains(id.id())) {
			wanted.add(id.id());
		}
	}

	/** A map was made, or given another id. */
	public void onSet(MapId id, MapItemSavedData map) {
		known.put(map, id.id());

		if (recording()) {
			dirty(id.id()).full = true;
		}
	}

	public void onPixel(MapItemSavedData map, int x, int y) {
		Integer id = known.get(map);

		if (id != null && recording()) {
			Dirty change = dirty(id);
			change.minX = Math.min(change.minX, x);
			change.minY = Math.min(change.minY, y);
			change.maxX = Math.max(change.maxX, x);
			change.maxY = Math.max(change.maxY, y);
		}
	}

	/** A map is to be saved: its pixels, banners or frames changed. */
	public void onDirty(MapItemSavedData map) {
		Integer id = known.get(map);

		if (id != null && recording()) {
			dirty(id).markers = true;
		}
	}

	/**
	 * At the end of a tick: the master broadcasts what changed and answers the nodes that asked
	 * for maps, and any other node sends the master what changed here and asks for what it lacks.
	 */
	public void flush() {
		if (!node.isConnected()) {
			return;
		}

		if (ids.shouldAsk()) {
			node.send(ids.ask(((MapIndexAccessor) storage().computeIfAbsent(MapIndex.TYPE)).lodecore$lastMapId() + 1));
		}

		// What changed waits until there is a master to send it to.
		if (master == Node.NO_NODE) {
			return;
		}

		if (master == node.nodeId()) {
			for (byte[] payload : writeChanges(MAP_STATE)) {
				node.send(Wire.broadcast(Wire.CLUSTER_SCOPE, payload));
			}

			for (Int2ObjectMap.Entry<IntList> request : requests.int2ObjectEntrySet()) {
				for (byte[] payload : writeWhole(request.getValue())) {
					node.send(Wire.direct(request.getIntKey(), payload));
				}
			}
		} else {
			for (byte[] payload : writeChanges(MAP_CHANGE)) {
				node.send(Wire.direct(master, payload));
			}

			if (!wanted.isEmpty()) {
				RegistryFriendlyByteBuf out = start(MAP_REQUEST);
				out.writeVarInt(wanted.size());

				for (int id : wanted) {
					out.writeVarInt(id);
				}

				node.send(Wire.direct(master, ByteBufUtil.getBytes(out)));
				requested.addAll(wanted);
			}
		}

		dirty.clear();
		wanted.clear();
		requests.clear();
	}

	public void onDisconnected() {
		master = Node.NO_NODE;
		dirty.clear();
		fresh.clear();
		wanted.clear();
		requested.clear();
		requests.clear();
		ids.onDisconnected();
	}

	// ---- payloads ----

	private RegistryFriendlyByteBuf start(int type) {
		RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), node.server().registryAccess());
		out.writeByte(type);
		return out;
	}

	private DynamicOps<Tag> ops() {
		return node.server().registryAccess().createSerializationContext(NbtOps.INSTANCE);
	}

	private List<byte[]> writeChanges(int type) {
		List<byte[]> payloads = new ArrayList<>();
		RegistryFriendlyByteBuf out = null;

		for (Int2ObjectMap.Entry<Dirty> entry : dirty.int2ObjectEntrySet()) {
			MapItemSavedData map = storage().get(type(entry.getIntKey()));

			if (map == null) {
				continue;
			}

			if (out == null) {
				out = start(type);
			}

			try {
				if (entry.getValue().full) {
					writeFull(out, entry.getIntKey(), map);
				} else {
					writePatch(out, entry.getIntKey(), map, entry.getValue());
				}
			} catch (RuntimeException e) {
				LOGGER.warn("Could not write map #{}: {}", entry.getIntKey(), e.toString());
			}

			if (out.writerIndex() >= PAYLOAD_SIZE) {
				payloads.add(ByteBufUtil.getBytes(out));
				out = null;
			}
		}

		if (out != null) {
			payloads.add(ByteBufUtil.getBytes(out));
		}

		return payloads;
	}

	/** Answers a node that asked for maps: each whole, or that the master has none. */
	private List<byte[]> writeWhole(IntList asked) {
		List<byte[]> payloads = new ArrayList<>();
		RegistryFriendlyByteBuf out = start(MAP_STATE);

		for (int id : asked) {
			MapItemSavedData map = storage().get(type(id));

			if (map == null) {
				out.writeByte(NONE);
				out.writeVarInt(id);
			} else {
				writeFull(out, id, map);
			}

			if (out.writerIndex() >= PAYLOAD_SIZE) {
				payloads.add(ByteBufUtil.getBytes(out));
				out = start(MAP_STATE);
			}
		}

		payloads.add(ByteBufUtil.getBytes(out));
		return payloads;
	}

	private void writeFull(RegistryFriendlyByteBuf out, int id, MapItemSavedData map) {
		out.writeByte(FULL);
		out.writeVarInt(id);
		out.writeNbt(MapItemSavedData.CODEC.encodeStart(ops(), map).getOrThrow());
	}

	private void writePatch(RegistryFriendlyByteBuf out, int id, MapItemSavedData map, Dirty change) {
		out.writeByte(PATCH);
		out.writeVarInt(id);
		boolean pixels = change.hasPixels();
		out.writeBoolean(pixels);

		if (pixels) {
			int width = change.maxX + 1 - change.minX;
			int height = change.maxY + 1 - change.minY;
			byte[] colors = new byte[width * height];

			for (int y = 0; y < height; y++) {
				System.arraycopy(map.colors, change.minX + (change.minY + y) * SIZE, colors, y * width, width);
			}

			out.writeByte(change.minX);
			out.writeByte(change.minY);
			out.writeByte(width);
			out.writeByte(height);
			out.writeByteArray(colors);
		}

		MapItemSavedDataAccessor markers = (MapItemSavedDataAccessor) map;
		CompoundTag tag = new CompoundTag();
		tag.put("banners", BANNERS.encodeStart(ops(), List.copyOf(markers.lodecore$bannerMarkers().values())).getOrThrow());
		tag.put("frames", FRAMES.encodeStart(ops(), List.copyOf(markers.lodecore$frameMarkers().values())).getOrThrow());
		out.writeNbt(tag);
	}

	/** Another node sent this node something, to it alone or to the whole cluster. */
	public void onPayload(int from, ByteBuffer payload) {
		try {
			RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload), node.server().registryAccess());
			int type = in.readByte();

			switch (type) {
				case MAP_STATE -> {
					if (from == master && master != node.nodeId()) {
						read(in);
					}
				}
				case MAP_CHANGE -> {
					if (isMaster()) {
						read(in);
					}
				}
				case MAP_REQUEST -> {
					if (isMaster()) {
						int count = in.readVarInt();
						IntList asked = requests.computeIfAbsent(from, key -> new IntArrayList());

						for (int i = 0; i < count; i++) {
							asked.add(in.readVarInt());
						}
					}
				}
				default -> LOGGER.warn("Node #{} sent an unknown payload {}", from, type);
			}
		} catch (IndexOutOfBoundsException | DecoderException | IllegalArgumentException | IllegalStateException e) {
			LOGGER.warn("Node #{} sent a malformed payload: {}", from, e.toString());
		}
	}

	private void read(RegistryFriendlyByteBuf in) {
		applying = true;

		try {
			while (in.isReadable()) {
				int record = in.readByte();
				int id = in.readVarInt();

				switch (record) {
					case FULL -> {
						takeFull(id, (CompoundTag) in.readNbt(NbtAccounter.unlimitedHeap()));
						fresh.add(id);
						requested.remove(id);
					}
					case PATCH -> takePatch(id, in);
					case NONE -> {
						fresh.add(id);
						requested.remove(id);

						// The master has none: this node's copy, if it has one, is the cluster's now.
						if (storage().get(type(id)) != null) {
							dirty(id).full = true;
						}
					}
					default -> throw new IllegalArgumentException("unknown map record " + record);
				}
			}
		} finally {
			applying = false;
		}
	}

	/**
	 * Takes on a whole map. A map's place, scale and dimension never change, so a copy here that
	 * has them is changed pixel by pixel, which tells the players carrying it what changed, and
	 * any other is replaced.
	 */
	private void takeFull(int id, CompoundTag tag) {
		MapItemSavedData incoming = MapItemSavedData.CODEC.parse(ops(), tag).getOrThrow();
		SavedDataType<MapItemSavedData> type = type(id);
		MapItemSavedData map = storage().get(type);

		if (map == null || map.centerX != incoming.centerX || map.centerZ != incoming.centerZ || map.scale != incoming.scale
				|| map.locked != incoming.locked || map.dimension != incoming.dimension) {
			storage().set(type, incoming);
			incoming.setDirty();
			known.put(incoming, id);

			if (master == node.nodeId()) {
				dirty(id).full = true;
			}

			return;
		}

		for (int i = 0; i < SIZE * SIZE; i++) {
			map.updateColor(i % SIZE, i / SIZE, incoming.colors[i]);
		}

		MapItemSavedDataAccessor markers = (MapItemSavedDataAccessor) incoming;
		takeMarkers(map, List.copyOf(markers.lodecore$bannerMarkers().values()), List.copyOf(markers.lodecore$frameMarkers().values()));
	}

	private void takePatch(int id, RegistryFriendlyByteBuf in) {
		boolean pixels = in.readBoolean();
		int startX = 0;
		int startY = 0;
		int width = 0;
		int height = 0;
		byte[] colors = new byte[0];

		if (pixels) {
			startX = in.readUnsignedByte();
			startY = in.readUnsignedByte();
			width = in.readUnsignedByte();
			height = in.readUnsignedByte();
			colors = in.readByteArray(SIZE * SIZE);
		}

		CompoundTag tag = (CompoundTag) in.readNbt(NbtAccounter.unlimitedHeap());
		MapItemSavedData map = storage().get(type(id));

		if (map == null) {
			if (!isMaster() && !requested.contains(id)) {
				wanted.add(id);
			}

			return;
		}

		if (startX + width > SIZE || startY + height > SIZE || colors.length != width * height) {
			throw new IllegalArgumentException("map patch out of bounds");
		}

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				map.updateColor(startX + x, startY + y, colors[x + y * width]);
			}
		}

		List<MapBanner> banners = BANNERS.parse(ops(), tag.get("banners")).result().orElse(List.of());
		List<MapFrame> frames = FRAMES.parse(ops(), tag.get("frames")).result().orElse(List.of());
		takeMarkers(map, banners, frames);
	}

	/** Takes on a map's banners and frames, as the game adds and removes them. */
	private void takeMarkers(MapItemSavedData map, List<MapBanner> banners, List<MapFrame> frames) {
		MapItemSavedDataAccessor accessor = (MapItemSavedDataAccessor) map;
		Map<String, MapBanner> haveBanners = accessor.lodecore$bannerMarkers();
		Map<String, MapFrame> haveFrames = accessor.lodecore$frameMarkers();
		boolean changed = false;
		Set<String> bannerIds = new HashSet<>();

		for (MapBanner banner : banners) {
			bannerIds.add(banner.getId());

			if (!banner.equals(haveBanners.get(banner.getId()))) {
				haveBanners.put(banner.getId(), banner);
				accessor.lodecore$addDecoration(banner.getDecoration(), null, banner.getId(), banner.pos().getX(), banner.pos().getZ(), 180.0, banner.name().orElse(null));
				changed = true;
			}
		}

		for (String id : List.copyOf(haveBanners.keySet())) {
			if (!bannerIds.contains(id)) {
				haveBanners.remove(id);
				accessor.lodecore$removeDecoration(id);
				changed = true;
			}
		}

		Set<String> frameIds = new HashSet<>();

		for (MapFrame frame : frames) {
			frameIds.add(frame.getId());
			MapFrame had = haveFrames.put(frame.getId(), frame);

			if (!frame.equals(had)) {
				if (had != null) {
					accessor.lodecore$removeDecoration(frameKey(had));
				}

				accessor.lodecore$addDecoration(MapDecorationTypes.FRAME, null, frameKey(frame), frame.pos().getX(), frame.pos().getZ(), frame.rotation(), null);
				changed = true;
			}
		}

		for (String id : List.copyOf(haveFrames.keySet())) {
			if (!frameIds.contains(id)) {
				accessor.lodecore$removeDecoration(frameKey(haveFrames.remove(id)));
				changed = true;
			}
		}

		if (changed) {
			map.setDirty();
		}
	}

	/** The key of a frame's decoration, as the game makes it. */
	private static String frameKey(MapFrame frame) {
		return "frame-" + frame.entityId();
	}
}
