package dev.lodecore;

import dev.lodecore.storage.PlayerProgress;
import dev.lodecore.storage.InventoryEscrow;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import dev.lodecore.net.Wire;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * Keeps each player's saved data (inventory, ender chest, health, position) with lodestar rather
 * than in this node's save, so that a player's items are in one place whichever node they join.
 *
 * <p>When a player joins, the game waits while this node asks lodestar for the player's data and
 * custody of it, and then loads that instead of its own file. While the player is here, their data
 * is captured once per checkpoint, including statistics and advancements. Compression and upload
 * run on the save connection after the snapshot is detached. When they leave, the last save goes back and
 * custody with it. lodestar lends a player's data to one node at a time, so a player cannot carry
 * their inventory to another node and leave a copy behind.
 *
 * <p>Missing central data starts a new player; worker caches are never authoritative.
 *
 * <p>Everything here runs on the game thread.
 */
public final class PlayerDataSync {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/players");

	/** Custody of a player who never arrived is given back after this long. */
	private static final int UNUSED_TICKS = 600;

	private final Node node;
	/** Data lodestar lent for players on their way in, empty if it had none. */
	private final Map<UUID, Optional<byte[]>> lent = new HashMap<>();
	/** Players whose data this node has custody of, and since when. */
	private final Map<UUID, Integer> custody = new HashMap<>();
	private final Set<UUID> requested = new HashSet<>();
	private final Map<UUID, byte[]> sent = new HashMap<>();


	PlayerDataSync(Node node) {
		this.node = node;
	}

	/**
	 * Whether a joining player's data is here to be loaded. Asks lodestar for it if not; the
	 * game asks again every tick until it is.
	 */
	public boolean isReady(UUID uuid) {
		if (!node.isConnected()) return false;
		if (lent.containsKey(uuid)) {
			return true;
		}

		if (requested.add(uuid)) {
			node.send(Wire.playerDataRequest(uuid));
		}

		return false;
	}

	/**
	 * The data to load for a player, as lodestar lent it; null starts a new player.
	 */
	public @Nullable CompoundTag load(UUID uuid) {
		Optional<byte[]> data = lent.get(uuid);

		if (data == null || data.isEmpty()) {
			return null;
		}

		try {
			CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(data.get()), NbtAccounter.create(64L << 20));
			PlayerProgress.prepare(node.server(), uuid, tag);
			return tag;
		} catch (IOException e) {
			throw new IllegalStateException("Cannot load authoritative player data for " + uuid, e);
		}
	}

	void onPlayerData(Wire.PlayerData data) {
		UUID uuid = data.uuid();
		requested.remove(uuid);
		custody.put(uuid, node.server().getTickCount());
		ServerPlayer online = node.server().getPlayerList().getPlayer(uuid);

		// Back in custody after a lost connection to lodestar: what is here is newer.
		if (online != null) {
			push(online);
			return;
		}

		ByteBuffer bytes = data.data();
		lent.put(uuid, bytes == null ? Optional.empty() : Optional.of(toArray(bytes)));
	}

	private static byte[] toArray(ByteBuffer buffer) {
		byte[] array = new byte[buffer.remaining()];
		buffer.duplicate().get(array);
		return array;
	}

	/** The game saved a player, as it does now and then and when they leave. */
	public void onSaved(ServerPlayer player) {
		if (custody.containsKey(player.getUUID())) {
			push(player);
		}
	}

	void onJoined(ServerPlayer player) {
		CompoundTag loaded = load(player.getUUID());
		if (loaded != null) {
			PlayerProgress.joined(player, loaded);
			InventoryEscrow.restore(player, loaded);
		}
		lent.remove(player.getUUID());

		// Custody given back while the player took long to arrive is asked for again.
		if (node.isConnected() && !custody.containsKey(player.getUUID()) && requested.add(player.getUUID())) {
			node.send(Wire.playerDataRequest(player.getUUID()));
		}
	}

	/** A player left. The game has saved them; custody goes back with that save. */
	public void onRemoved(ServerPlayer player) {
		UUID uuid = player.getUUID();
		sent.remove(uuid);
		lent.remove(uuid);

		if (custody.remove(uuid) != null) {
			node.send(Wire.playerDataRelease(uuid));
		}
	}

	void tick() {
		int now = node.server().getTickCount();

		if (now % 20 != 0) return;

		for (Iterator<Map.Entry<UUID, Integer>> it = custody.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Integer> entry = it.next();
			UUID uuid = entry.getKey();

			if (node.server().getPlayerList().getPlayer(uuid) == null && now - entry.getValue() > UNUSED_TICKS) {
				it.remove();
				lent.remove(uuid);
				node.send(Wire.playerDataRelease(uuid));
			}
		}
	}

	private void push(ServerPlayer player) {
		try {
			byte[] data = write(player);
			if (java.util.Arrays.equals(sent.get(player.getUUID()), data)) return;
			node.send(Wire.playerDataSave(player.getUUID(), data));
			sent.put(player.getUUID(), data);
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("Could not save authoritative player data for " + player.getUUID(), e);
		}
	}

	/** Captures live state only on the game thread; the returned tags belong to the save batch. */
	Map<UUID, CompoundTag> snapshot() {
		Map<UUID, CompoundTag> players = new HashMap<>();
		for (ServerPlayer player : node.server().getPlayerList().getPlayers()) {
			if (custody.containsKey(player.getUUID())) players.put(player.getUUID(), capture(player));
		}
		return players;
	}

	private CompoundTag capture(ServerPlayer player) {
		try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(player.problemPath(), LOGGER)) {
			TagValueOutput output = TagValueOutput.createWithContext(reporter, player.registryAccess());
			player.saveWithoutId(output);
			CompoundTag tag = output.buildResult();
			PlayerProgress.capture(player, tag);
			InventoryEscrow.capture(player, tag);
			return tag;
		}
	}

	/** Immediate lifecycle/handoff save; checkpoint compression uses the background save executor. */
	public byte[] write(ServerPlayer player) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		NbtIo.writeCompressed(capture(player), bytes);
		return bytes.toByteArray();
	}

	/** Reads a player's saved data as lodestar keeps it, brought up to this version of the game. */
	public @Nullable CompoundTag parse(UUID uuid, byte[] data) {
		try {
			CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
			return DataFixTypes.PLAYER.updateToCurrentVersion(node.server().getFixerUpper(), tag, NbtUtils.getDataVersion(tag));
		} catch (IOException e) {
			LOGGER.error("The data of player {} is unreadable", uuid, e);
			return null;
		}
	}

	/** A player moved to another node. Custody of their data went with them, through lodestar. */
	public void onHandedOff(UUID uuid) {
		custody.remove(uuid);
		sent.remove(uuid);
		lent.remove(uuid);
		requested.remove(uuid);
	}

	/** A player moved here from another node, and with them custody of their data. */
	public void onHandedIn(ServerPlayer player) {
		UUID uuid = player.getUUID();
		custody.put(uuid, node.server().getTickCount());
		sent.remove(uuid);
		lent.remove(uuid);
		requested.remove(uuid);
	}

	/** Back in touch with lodestar: custody of the players here has to be asked for again. */
	void onConnected() {
		for (ServerPlayer player : node.server().getPlayerList().getPlayers()) {
			if (requested.add(player.getUUID())) {
				node.send(Wire.playerDataRequest(player.getUUID()));
			}
		}
	}

	void onDisconnected() {
		lent.clear();
		custody.clear();
		requested.clear();
		sent.clear();
	}
}
