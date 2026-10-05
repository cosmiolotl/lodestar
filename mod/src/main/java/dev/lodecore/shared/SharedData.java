package dev.lodecore.shared;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.lodecore.Node;
import dev.lodecore.net.Wire;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * What a server keeps once for all of its dimensions besides the world state, kept the same on
 * every node: the scoreboard and its teams, the boss bars made with {@code /bossbar}, command
 * storage, stopwatches, force-loaded chunks, and the operator, whitelist and ban lists.
 *
 * <p>All of it is kept by the master of the cluster's state, as lodestar names it. Each kind of
 * data is a set of keys, each with a value or none: a score is the key of a holder and an
 * objective, with the score as its value. A change made on any node is made there at once, so
 * that its players see it and its commands can read it back, and at the end of the tick the node
 * sends the master the keys it changed with their values as they are then. The master takes them
 * on, in the order they were sent, and broadcasts them with its own changes as the keys' new
 * values; every other node takes those on. A key changed on two nodes in the same tick ends up
 * with whichever change reached the master last, on every node. Until the master can have
 * answered, a node ignores what the master says of a key it changed itself.
 *
 * <p>Some changes do more than change one key: removing an objective removes its scores too. Such
 * a removal is sent as it happened, and stays in the order of the changes around it, even if the
 * key comes back in the same tick.
 *
 * <p>A node that joins the cluster, or whose master changes, asks the master for all of it, and
 * takes it on in place of its own, removing whatever the master does not have. Cut off from
 * lodestar, a node keeps its own, as a standalone server does.
 *
 * <p>Everything here runs on the game thread.
 */
public final class SharedData {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/shared");

	// Payloads, broadcast by the master or sent to one node.
	/** Keys and their values, from the master: what changed, or all of it, to a node that asked. */
	private static final int DATA = 13;
	/** Asks the master for all of it. */
	private static final int DATA_REQUEST = 14;
	/** Keys that changed on a node other than the master, with their values, sent to the master. */
	private static final int DATA_CHANGE = 15;

	/** In a {@link #DATA} payload: part of all of the data, which replaces what the receiver has. */
	private static final int WHOLE = 1;
	/** In a {@link #DATA} payload: the last part of all of the data. */
	private static final int LAST = 2;

	/** Payloads are cut at about this size, well under the largest frame lodestar takes. */
	private static final int PAYLOAD_SIZE = 1 << 20;

	/**
	 * One kind of data: a set of keys, each with a value. The values are whatever the kind needs to
	 * remember of a key, as NBT.
	 */
	public abstract static class Kind {
		final String name;
		int id = -1;

		protected Kind(String name) {
			this.name = name;
		}

		/** The value a key has here, or null if it has none. */
		protected abstract @Nullable CompoundTag get(String key);

		/** Gives a key a value, or takes it away: null. Goes through the game's own setters. */
		protected abstract void set(String key, @Nullable CompoundTag value);

		/** Every key that has a value here. */
		protected abstract Collection<String> keys();
	}

	/** A key that changed here: to be sent with its value at the end of the tick, or as removed. */
	private record Change(Kind kind, String key, boolean removal) {
	}

	private final Node node;
	private final List<Kind> kinds = new ArrayList<>();
	private final ScoreboardSync scoreboard;
	private final BossBarSync bossBars;
	private final ServerDataSync serverData;
	private final UserListSync userLists;
	/** The master of the cluster's state, as lodestar has named it. */
	private int master = Node.NO_NODE;
	/** Set while taking on what another node sent, so that it is not taken for a change made here. */
	private boolean applying;
	/**
	 * The keys that changed here since the end of the last tick, in the order they changed, by
	 * {@link #entry}. A removal that is followed by the key changing again is kept under a key of
	 * its own, so that both are sent.
	 */
	private final LinkedHashMap<Object, Change> pending = new LinkedHashMap<>();
	/** The tick at whose end this node last sent the master each key, by {@link #entry}. */
	private final Object2IntMap<String> sentAt = new Object2IntOpenHashMap<>();
	/** Nodes that asked for all of it, answered at the end of the tick. */
	private final IntList requests = new IntArrayList();
	/** While taking on all of the data from the master, part by part: the keys it has, by kind. */
	private @Nullable Map<Kind, Set<String>> whole;

	public SharedData(Node node) {
		this.node = node;
		sentAt.defaultReturnValue(Integer.MIN_VALUE);
		// In the order all of it is sent and taken on: objectives before their scores, teams
		// before their members.
		scoreboard = new ScoreboardSync(this, node);
		bossBars = new BossBarSync(this, node);
		serverData = new ServerDataSync(this, node);
		userLists = new UserListSync(this, node);
	}

	/** Adds a kind of data. Every node must add the same kinds in the same order. */
	Kind register(Kind kind) {
		kind.id = kinds.size();
		kinds.add(kind);
		return kind;
	}

	public ScoreboardSync scoreboard() {
		return scoreboard;
	}

	public BossBarSync bossBars() {
		return bossBars;
	}

	public ServerDataSync serverData() {
		return serverData;
	}

	public UserListSync userLists() {
		return userLists;
	}

	/** Whether a payload sent to this node alone, or broadcast, is for this class. */
	public static boolean handles(int kind) {
		return kind == DATA || kind == DATA_REQUEST || kind == DATA_CHANGE;
	}

	/** Gets ready to keep track of changes. Called once the levels are loaded. */
	public void start() {
		serverData.start();
	}

	// ---- who keeps it ----

	/** Whether this node keeps the data: it is the master, or cut off from lodestar. */
	public boolean isMaster() {
		return !node.isConnected() || master == node.nodeId();
	}

	public void onMaster(int master) {
		int previous = this.master;
		this.master = master;

		if (previous != master && master != Node.NO_NODE && master != node.nodeId()) {
			whole = null;
			node.send(Wire.direct(master, new byte[] {DATA_REQUEST}));
		}
	}

	// ---- changes made here ----

	/**
	 * Whether a change made now is to be passed on: always on the master, which passes on what it
	 * takes from the others too, and on any other node unless it comes from the master.
	 */
	boolean recording() {
		return node.isConnected() && (!applying || master == node.nodeId());
	}

	private static String entry(Kind kind, String key) {
		return kind.id + ":" + key;
	}

	/** A key changed here. Its value is taken at the end of the tick. */
	void changed(Kind kind, String key) {
		if (!recording()) {
			return;
		}

		String entry = entry(kind, key);
		Change previous = pending.remove(entry);

		if (previous != null && previous.removal()) {
			// Sent as it happened, before the key's new value.
			pending.put(new Object(), previous);
		}

		pending.put(entry, new Change(kind, key, false));
	}

	/**
	 * A key was taken away here in a way that does more than take its value away, such as an
	 * objective, whose scores go with it. It is sent as such even if the key comes back.
	 */
	void removed(Kind kind, String key) {
		if (!recording()) {
			return;
		}

		String entry = entry(kind, key);
		pending.remove(entry);
		pending.put(entry, new Change(kind, key, true));
	}

	/**
	 * At the end of a tick: the master broadcasts what changed and answers the nodes that asked for
	 * all of it, and any other node sends the master what changed here.
	 */
	public void flush() {
		// What changed waits until there is a master to send it to.
		if (!node.isConnected() || master == Node.NO_NODE) {
			return;
		}

		bossBars.collectMembership();
		int tick = node.server().getTickCount();

		if (master == node.nodeId()) {
			if (!pending.isEmpty()) {
				for (byte[] payload : write(DATA, 0, pending.values())) {
					node.send(Wire.broadcast(Wire.CLUSTER_SCOPE, payload));
				}
			}

			if (!requests.isEmpty()) {
				List<Change> all = all();

				for (int to : requests) {
					for (byte[] payload : write(DATA, WHOLE, all)) {
						node.send(Wire.direct(to, payload));
					}
				}
			}
		} else if (!pending.isEmpty()) {
			for (byte[] payload : write(DATA_CHANGE, 0, pending.values())) {
				node.send(Wire.direct(master, payload));
			}

			for (Change change : pending.values()) {
				sentAt.put(entry(change.kind(), change.key()), tick);
			}
		}

		pending.clear();
		requests.clear();
		sentAt.values().removeIf(sent -> sent < tick);
	}

	/** Every key of every kind that has a value, in the order of the kinds. */
	private List<Change> all() {
		List<Change> all = new ArrayList<>();

		for (Kind kind : kinds) {
			for (String key : kind.keys()) {
				all.add(new Change(kind, key, false));
			}
		}

		return all;
	}

	public void onDisconnected() {
		master = Node.NO_NODE;
		pending.clear();
		sentAt.clear();
		requests.clear();
		whole = null;
		bossBars.onDisconnected();
	}

	// ---- payloads ----

	/**
	 * Writes keys with their values as they are now, cut into payloads of a manageable size. A
	 * payload of all of the data is flagged so, and its last part as the last, even if it is empty.
	 */
	private List<byte[]> write(int type, int flags, Collection<Change> changes) {
		List<byte[]> payloads = new ArrayList<>();
		RegistryFriendlyByteBuf out = null;

		for (Change change : changes) {
			if (out == null) {
				out = start(type, flags);
			}

			CompoundTag value = null;

			try {
				value = change.removal() ? null : change.kind().get(change.key());
			} catch (RuntimeException e) {
				LOGGER.warn("Could not write {} {}: {}", change.kind().name, change.key(), e.toString());
			}

			out.writeVarInt(change.kind().id);
			out.writeUtf(change.key(), Short.MAX_VALUE);
			out.writeBoolean(value != null);

			if (value != null) {
				out.writeNbt(value);
			}

			if (out.writerIndex() >= PAYLOAD_SIZE) {
				payloads.add(ByteBufUtil.getBytes(out));
				out = null;
			}
		}

		if ((flags & WHOLE) != 0) {
			if (out == null) {
				out = start(type, flags);
			}

			out.setByte(1, flags | LAST);
		}

		if (out != null) {
			payloads.add(ByteBufUtil.getBytes(out));
		}

		return payloads;
	}

	private RegistryFriendlyByteBuf start(int type, int flags) {
		RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), node.server().registryAccess());
		out.writeByte(type);

		if (type == DATA) {
			out.writeByte(flags);
		}

		return out;
	}

	/** Another node sent this node something, to it alone or to the whole cluster. */
	public void onPayload(int from, ByteBuffer payload) {
		try {
			RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload), node.server().registryAccess());
			int type = in.readByte();

			switch (type) {
				// lodestar relays a broadcast only from the master, but an answer to a request can
				// come from one that has just given up being it.
				case DATA -> {
					if (from == master && master != node.nodeId()) {
						read(in, in.readByte(), false);
					}
				}
				case DATA_CHANGE -> {
					if (isMaster()) {
						read(in, 0, true);
					}
				}
				case DATA_REQUEST -> {
					if (isMaster()) {
						requests.add(from);
					}
				}
				default -> LOGGER.warn("Node #{} sent an unknown payload {}", from, type);
			}
		} catch (IndexOutOfBoundsException | DecoderException | IllegalArgumentException e) {
			LOGGER.warn("Node #{} sent a malformed payload: {}", from, e.toString());
		}
	}

	/**
	 * Takes on keys another node sent: the master's values, or, on the master, a change made on
	 * another node, which is then passed on with this node's own.
	 */
	private void read(RegistryFriendlyByteBuf in, int flags, boolean change) {
		if ((flags & WHOLE) != 0 && whole == null) {
			whole = new HashMap<>();
		}

		applying = true;

		try {
			while (in.isReadable()) {
				int id = in.readVarInt();

				if (id < 0 || id >= kinds.size()) {
					throw new IllegalArgumentException("unknown kind of data " + id);
				}

				Kind kind = kinds.get(id);
				String key = in.readUtf(Short.MAX_VALUE);
				CompoundTag value = in.readBoolean() ? (CompoundTag) in.readNbt(NbtAccounter.unlimitedHeap()) : null;

				if ((flags & WHOLE) != 0 && value != null) {
					whole.computeIfAbsent(kind, k -> new HashSet<>()).add(key);
				}

				if (change || !isStale(kind, key)) {
					set(kind, key, value);
				}
			}

			if ((flags & LAST) != 0 && whole != null) {
				removeMissing(whole);
				whole = null;
			}
		} finally {
			applying = false;
		}
	}

	/**
	 * Whether this node changed a key more recently than the master can have heard: the change waits
	 * to be sent (commands run between ticks, before what the master sent during the last one is
	 * taken on), or was sent at the end of the last tick.
	 */
	private boolean isStale(Kind kind, String key) {
		String entry = entry(kind, key);
		return pending.containsKey(entry) || sentAt.getInt(entry) >= node.server().getTickCount();
	}

	private void set(Kind kind, String key, @Nullable CompoundTag value) {
		try {
			kind.set(key, value);
		} catch (RuntimeException e) {
			LOGGER.warn("Could not take on {} {}: {}", kind.name, key, e.toString());
		}
	}

	/** Takes away what the master does not have, last kind first: members before their teams. */
	private void removeMissing(Map<Kind, Set<String>> theirs) {
		for (int i = kinds.size() - 1; i >= 0; i--) {
			Kind kind = kinds.get(i);
			Set<String> keys = theirs.getOrDefault(kind, Set.of());

			for (String key : List.copyOf(kind.keys())) {
				if (!keys.contains(key) && !isStale(kind, key)) {
					set(kind, key, null);
				}
			}
		}
	}
}
