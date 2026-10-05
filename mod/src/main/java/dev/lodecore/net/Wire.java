package dev.lodecore.net;

import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The node's side of the lodestar wire protocol.
 *
 * <p>This mirrors {@code crates/lode-protocol/src/lib.rs}, which is the reference: every frame is a
 * big-endian {@code u32} length followed by a body, and the body is a one byte message id followed
 * by fixed-width big-endian fields. Only the messages a node uses are implemented here.
 *
 * <p>What goes inside {@code publish} and {@code direct} payloads is this mod's own business; see
 * {@code dev.lodecore.replication}.
 */
public final class Wire {
	public static final int PROTOCOL_VERSION = 9;
	public static final int MAX_FRAME = 4 * 1024 * 1024;
	/** A region is a square of {@code 1 << REGION_SHIFT} chunks on a side. */
	public static final int REGION_SHIFT = 3;
	/** Entity ids are handed out in blocks of {@code 1 << ID_BLOCK_SHIFT}. */
	public static final int ID_BLOCK_SHIFT = 16;
	/** Map ids and raid ids are handed out in blocks of {@code 1 << COUNTER_BLOCK_SHIFT}. */
	public static final int COUNTER_BLOCK_SHIFT = 12;
	/** The counter of map ids. */
	public static final int COUNTER_MAP_IDS = 0;
	/** The counter of raid ids. */
	public static final int COUNTER_RAID_IDS = 1;
	/**
	 * The scope of the state a server keeps once for all of its dimensions, such as the time of
	 * day and the weather. Every other scope is a dimension, by lodestar's id for it.
	 */
	public static final int CLUSTER_SCOPE = -1;

	private static final int HELLO = 0x01;
	private static final int HEARTBEAT = 0x02;
	private static final int RESOLVE_DIMENSION = 0x03;
	private static final int PLAYER_JOIN = 0x04;
	private static final int PLAYER_LEAVE = 0x05;
	private static final int SUBSCRIBE = 0x07;
	private static final int UNSUBSCRIBE = 0x08;
	private static final int PUBLISH = 0x09;
	private static final int DIRECT = 0x0A;
	private static final int SET_TICKING = 0x0D;
	private static final int TICK_DONE = 0x0E;
	private static final int SYNCED = 0x0F;
	private static final int PLAYER_AT = 0x10;
	private static final int CLAIM = 0x11;
	private static final int CLAIM_ANSWER = 0x12;
	private static final int RELEASE = 0x13;
	private static final int PLAYER_DATA_REQUEST = 0x14;
	private static final int PLAYER_DATA_SAVE = 0x15;
	private static final int PLAYER_DATA_RELEASE = 0x16;
	private static final int ID_BLOCK_REQUEST = 0x17;
	private static final int HANDOFF_REQUEST = 0x18;
	private static final int HANDOFF_READY = 0x19;
	private static final int HANDOFF_STATE = 0x1A;
	private static final int HANDOFF_ABORT = 0x1B;
	private static final int HANDOFF_DONE = 0x1C;
	private static final int BROADCAST = 0x1F;
	private static final int COUNTER_BLOCK_REQUEST = 0x20;
	private static final int TICK_INTERVAL = 0x21;
	private static final int ANNOUNCE = 0x22;

	private static final int WELCOME = 0x81;
	private static final int REJECTED = 0x82;
	private static final int DIMENSION_RESOLVED = 0x83;
	private static final int RELAY = 0x85;
	private static final int DIRECT_RELAY = 0x86;
	private static final int PLAYER_JOINED = 0x8B;
	private static final int PLAYER_LEFT = 0x8C;
	private static final int CHUNK_DEMAND = 0x8E;
	private static final int TICK = 0x8F;
	private static final int REGION_OWNER = 0x90;
	private static final int CLAIM_REQUEST = 0x91;
	private static final int CLAIM_RESULT = 0x92;
	private static final int CUSTODY = 0x93;
	private static final int RELEASED = 0x94;
	private static final int PLAYER_DATA = 0x95;
	private static final int ID_BLOCK = 0x96;
	private static final int HANDOFF_PREPARE = 0x97;
	private static final int HANDOFF_CUT = 0x98;
	private static final int HANDOFF_ARRIVE = 0x99;
	private static final int HANDOFF_CANCEL = 0x9A;
	private static final int MASTER = 0x9E;
	private static final int BROADCAST_RELAY = 0x9F;
	private static final int COUNTER_BLOCK = 0xA0;
	private static final int ANNOUNCE_RELAY = 0xA1;

	private static final int ROLE_NODE = 0;

	private Wire() {
	}

	/** A message from lodestar. */
	public sealed interface Inbound {
	}

	public record Welcome(int nodeId) implements Inbound {
	}

	public record Rejected(String reason) implements Inbound {
	}

	public record DimensionResolved(String name, int id) implements Inbound {
	}

	/**
	 * Which node simulates a region of a dimension this node has, or {@code 0} once nobody has any
	 * of it loaded. Regions are in region coordinates: chunk coordinates shifted right by
	 * {@link #REGION_SHIFT}.
	 */
	public record RegionOwner(int dimension, int regionX, int regionZ, int owner) implements Inbound {
	}

	/** The owner of a region is asked to hand an object in it to another node. */
	public record ClaimRequest(int claim, int claimant, int dimension, int chunkX, int chunkZ, ByteBuffer object) implements Inbound {
	}

	/** Whether this node got custody of what it claimed, and if so, the object's state. */
	public record ClaimResult(int claim, boolean granted, ByteBuffer payload) implements Inbound {
	}

	/** Which node has custody of an object, or {@code 0} once its region's owner has it back. */
	public record Custody(int dimension, ByteBuffer object, int holder) implements Inbound {
	}

	/** An object handed back to this node, the owner of its region, with its state. */
	public record Released(int dimension, int chunkX, int chunkZ, ByteBuffer object, ByteBuffer payload) implements Inbound {
	}

	/** A player's saved data, or null if lodestar has none yet. */
	public record PlayerData(UUID uuid, @org.jspecify.annotations.Nullable ByteBuffer data) implements Inbound {
	}

	/** A player is homed on a node, which is the authority for everything about them. */
	public record PlayerJoined(int node, UUID uuid, String name) implements Inbound {
	}

	public record PlayerLeft(int node, UUID uuid) implements Inbound {
	}

	/**
	 * How much the other nodes need of a chunk. Only sent to the owner of its region, and to the
	 * node the region is being handed to.
	 */
	public record ChunkDemand(int dimension, int chunkX, int chunkZ, Demand demand) implements Inbound {
	}

	public enum Demand {
		/** No other node has the chunk loaded. */
		NONE,
		/** Another node has the chunk loaded, so the owner must hold it too. */
		LOADED,
		/** Another node would tick the chunk, so the owner must tick it. */
		TICKING
	}

	/** A block of entity ids for this node alone to hand out. */
	public record IdBlock(int block) implements Inbound {
	}

	/** Something another node announced to the whole cluster, such as chat. */
	public record AnnounceRelay(int from, ByteBuffer payload) implements Inbound {
	}

	/** A block of one of the counters' ids, such as map ids, for this node alone to hand out. */
	public record CounterBlock(int counter, int block) implements Inbound {
	}

	/**
	 * Get ready to take over a player homed on another node: load the world around where they
	 * were last reported, and expect the proxy to log them in here, presenting {@code token}.
	 */
	public record HandoffPrepare(UUID uuid, long token, int dimension, int chunkX, int chunkZ) implements Inbound {
	}

	/**
	 * Let go of a player homed here, to node {@code to}, once the first {@code frames} packets of
	 * their connection have been handled: that is all the proxy has passed on.
	 */
	public record HandoffCut(UUID uuid, int to, int dimension, long frames) implements Inbound {
	}

	/** Take over a player another node has let go of: their data, and what their client was sent. */
	public record HandoffArrive(UUID uuid, ByteBuffer data, ByteBuffer state) implements Inbound {
	}

	/** Moving a player is off. */
	public record HandoffCancel(UUID uuid) implements Inbound {
	}

	/** Run a tick, and answer with {@link #tickDone} when it is over. */
	public record Tick(long tick) implements Inbound {
	}

	/** Something another subscriber of a chunk published. */
	public record Relay(int from, int dimension, int chunkX, int chunkZ, ByteBuffer payload) implements Inbound {
	}

	/** Something another node sent to this node alone. */
	public record DirectRelay(int from, ByteBuffer payload) implements Inbound {
	}

	/**
	 * The node that keeps the state of a scope, {@link #CLUSTER_SCOPE} or a dimension, which every
	 * other node of the scope adopts. Only ever changes between two ticks.
	 */
	public record Master(int scope, int master) implements Inbound {
	}

	/** Something the master of a scope this node is of broadcast. */
	public record BroadcastRelay(int from, int scope, ByteBuffer payload) implements Inbound {
	}

	/** A message this node has no use for yet. */
	public record Ignored(int id) implements Inbound {
	}

	public static final class MalformedFrameException extends Exception {
		MalformedFrameException(String message) {
			super(message);
		}
	}

	public static byte[] hello(String token, String name, String gameAddress, int maxPlayers) {
		return new Frame(HELLO).u32(PROTOCOL_VERSION).string(token).string(name).u8(ROLE_NODE).string(gameAddress).u32(maxPlayers).finish();
	}

	public static byte[] heartbeat(int msptMicros, int players) {
		return new Frame(HEARTBEAT).u32(msptMicros).u32(players).finish();
	}

	public static byte[] resolveDimension(String name) {
		return new Frame(RESOLVE_DIMENSION).string(name).finish();
	}

	public static byte[] playerJoin(UUID uuid, String name) {
		return new Frame(PLAYER_JOIN).uuid(uuid).string(name).finish();
	}

	public static byte[] playerLeave(UUID uuid) {
		return new Frame(PLAYER_LEAVE).uuid(uuid).finish();
	}

	public static byte[] subscribe(int dimension, int chunkX, int chunkZ) {
		return new Frame(SUBSCRIBE).u32(dimension).u32(chunkX).u32(chunkZ).finish();
	}

	public static byte[] unsubscribe(int dimension, int chunkX, int chunkZ) {
		return new Frame(UNSUBSCRIBE).u32(dimension).u32(chunkX).u32(chunkZ).finish();
	}

	/** Says whether this node would tick a chunk it subscribes to, were it the owner of its region. */
	public static byte[] setTicking(int dimension, int chunkX, int chunkZ, boolean ticking) {
		return new Frame(SET_TICKING).u32(dimension).u32(chunkX).u32(chunkZ).u8(ticking ? 1 : 0).finish();
	}

	/** Says that this node's copy of a chunk matches the owner's. */
	public static byte[] synced(int dimension, int chunkX, int chunkZ) {
		return new Frame(SYNCED).u32(dimension).u32(chunkX).u32(chunkZ).finish();
	}

	/** Says where a player homed here is. */
	public static byte[] playerAt(UUID uuid, int dimension, int chunkX, int chunkZ) {
		return new Frame(PLAYER_AT).uuid(uuid).u32(dimension).u32(chunkX).u32(chunkZ).finish();
	}

	/** Asks for custody of an object in a chunk. */
	public static byte[] claim(int claim, int dimension, int chunkX, int chunkZ, byte[] object) {
		return new Frame(CLAIM).u32(claim).u32(dimension).u32(chunkX).u32(chunkZ).blob(object).finish();
	}

	/** Answers a {@link ClaimRequest}, handing over the object's state if granted. */
	public static byte[] claimAnswer(int claim, int claimant, boolean granted, byte[] payload) {
		return new Frame(CLAIM_ANSWER).u32(claim).u32(claimant).u8(granted ? 1 : 0).blob(payload).finish();
	}

	/** Gives up custody of an object, which is now in the given chunk, with its state. */
	public static byte[] release(int dimension, int chunkX, int chunkZ, byte[] object, byte[] payload) {
		return new Frame(RELEASE).u32(dimension).u32(chunkX).u32(chunkZ).blob(object).blob(payload).finish();
	}

	public static byte[] playerDataRequest(UUID uuid) {
		return new Frame(PLAYER_DATA_REQUEST).uuid(uuid).finish();
	}

	public static byte[] playerDataSave(UUID uuid, byte[] data) {
		return new Frame(PLAYER_DATA_SAVE).uuid(uuid).blob(data).finish();
	}

	public static byte[] playerDataRelease(UUID uuid) {
		return new Frame(PLAYER_DATA_RELEASE).uuid(uuid).finish();
	}

	public static byte[] idBlockRequest() {
		return new Frame(ID_BLOCK_REQUEST).finish();
	}

	/**
	 * Asks for a block of a counter's ids. {@code floor} is the lowest id this node may hand out:
	 * ids below it may be in use already, in this node's own save.
	 */
	public static byte[] counterBlockRequest(int counter, int floor) {
		return new Frame(COUNTER_BLOCK_REQUEST).u8(counter).u32(floor).finish();
	}

	/** Announces to every other node. The payload is opaque to lodestar. */
	public static byte[] announce(byte[] payload) {
		return new Frame(ANNOUNCE).blob(payload).finish();
	}

	/** As the master of the cluster's state, says how long a tick should take; 0 for as fast as can be. */
	public static byte[] tickInterval(long nanos) {
		return new Frame(TICK_INTERVAL).u64(nanos).finish();
	}

	/** Asks lodestar to move a player to another node. */
	public static byte[] handoffRequest(UUID uuid, int to) {
		return new Frame(HANDOFF_REQUEST).uuid(uuid).u32(to).finish();
	}

	/** This node is ready to take over a player. */
	public static byte[] handoffReady(UUID uuid) {
		return new Frame(HANDOFF_READY).uuid(uuid).finish();
	}

	/** This node has let go of a player: their data, and what their client was last sent. */
	public static byte[] handoffState(UUID uuid, byte[] data, byte[] state) {
		return new Frame(HANDOFF_STATE).uuid(uuid).blob(data).blob(state).finish();
	}

	/** This node gives up its part in moving a player. */
	public static byte[] handoffAbort(UUID uuid) {
		return new Frame(HANDOFF_ABORT).uuid(uuid).finish();
	}

	/** This node has taken over a player. */
	public static byte[] handoffDone(UUID uuid) {
		return new Frame(HANDOFF_DONE).uuid(uuid).finish();
	}

	public static byte[] tickDone(long tick) {
		return new Frame(TICK_DONE).u64(tick).finish();
	}

	/** Publishes to every other subscriber of a chunk. The payload is opaque to lodestar. */
	public static byte[] publish(int dimension, int chunkX, int chunkZ, byte[] payload) {
		return new Frame(PUBLISH).u32(dimension).u32(chunkX).u32(chunkZ).blob(payload).finish();
	}

	/** Sends to one node. The payload is opaque to lodestar. */
	public static byte[] direct(int to, byte[] payload) {
		return new Frame(DIRECT).u32(to).blob(payload).finish();
	}

	/**
	 * Sends to every other node of a scope this node is the master of. The payload is opaque to
	 * lodestar.
	 */
	public static byte[] broadcast(int scope, byte[] payload) {
		return new Frame(BROADCAST).u32(scope).blob(payload).finish();
	}

	/** Decodes a frame body, that is, a frame without its length prefix. */
	public static Inbound decode(ByteBuffer body) throws MalformedFrameException {
		try {
			int id = body.get() & 0xff;
			Inbound message = switch (id) {
				case WELCOME -> new Welcome(body.getInt());
				case REJECTED -> new Rejected(string(body));
				case DIMENSION_RESOLVED -> new DimensionResolved(string(body), body.getInt());
				case REGION_OWNER -> new RegionOwner(body.getInt(), body.getInt(), body.getInt(), body.getInt());
				case PLAYER_JOINED -> new PlayerJoined(body.getInt(), uuid(body), string(body));
				case PLAYER_LEFT -> new PlayerLeft(body.getInt(), uuid(body));
				case CLAIM_REQUEST -> new ClaimRequest(body.getInt(), body.getInt(), body.getInt(), body.getInt(), body.getInt(), blob(body));
				case CLAIM_RESULT -> new ClaimResult(body.getInt(), flag(body.get()), blob(body));
				case CUSTODY -> new Custody(body.getInt(), blob(body), body.getInt());
				case RELEASED -> new Released(body.getInt(), body.getInt(), body.getInt(), blob(body), blob(body));
				case PLAYER_DATA -> new PlayerData(uuid(body), flag(body.get()) ? blob(body) : null);
				case CHUNK_DEMAND -> new ChunkDemand(body.getInt(), body.getInt(), body.getInt(), demand(body.get()));
				case TICK -> new Tick(body.getLong());
				case RELAY -> new Relay(body.getInt(), body.getInt(), body.getInt(), body.getInt(), blob(body));
				case DIRECT_RELAY -> new DirectRelay(body.getInt(), blob(body));
				case MASTER -> new Master(body.getInt(), body.getInt());
				case BROADCAST_RELAY -> new BroadcastRelay(body.getInt(), body.getInt(), blob(body));
				case ID_BLOCK -> new IdBlock(body.getInt());
				case COUNTER_BLOCK -> new CounterBlock(body.get() & 0xff, body.getInt());
				case ANNOUNCE_RELAY -> new AnnounceRelay(body.getInt(), blob(body));
				case HANDOFF_PREPARE -> new HandoffPrepare(uuid(body), body.getLong(), body.getInt(), body.getInt(), body.getInt());
				case HANDOFF_CUT -> new HandoffCut(uuid(body), body.getInt(), body.getInt(), body.getLong());
				case HANDOFF_ARRIVE -> new HandoffArrive(uuid(body), blob(body), blob(body));
				case HANDOFF_CANCEL -> new HandoffCancel(uuid(body));
				default -> {
					body.position(body.limit());
					yield new Ignored(id);
				}
			};

			if (body.hasRemaining()) {
				throw new MalformedFrameException("trailing bytes after message " + id);
			}

			return message;
		} catch (BufferUnderflowException e) {
			throw new MalformedFrameException("message is truncated");
		}
	}

	private static String string(ByteBuffer body) throws MalformedFrameException {
		int length = body.getShort() & 0xffff;

		if (length > body.remaining()) {
			throw new BufferUnderflowException();
		}

		ByteBuffer slice = body.slice(body.position(), length);
		body.position(body.position() + length);

		try {
			return StandardCharsets.UTF_8.newDecoder().decode(slice).toString();
		} catch (CharacterCodingException e) {
			throw new MalformedFrameException("string is not valid UTF-8");
		}
	}

	private static boolean flag(byte value) throws MalformedFrameException {
		return switch (value) {
			case 0 -> false;
			case 1 -> true;
			default -> throw new MalformedFrameException("unknown flag " + value);
		};
	}

	private static UUID uuid(ByteBuffer body) {
		return new UUID(body.getLong(), body.getLong());
	}

	private static Demand demand(byte tag) throws MalformedFrameException {
		return switch (tag) {
			case 0 -> Demand.NONE;
			case 1 -> Demand.LOADED;
			case 2 -> Demand.TICKING;
			default -> throw new MalformedFrameException("unknown demand " + tag);
		};
	}

	private static ByteBuffer blob(ByteBuffer body) {
		int length = body.getInt();

		if (length < 0 || length > body.remaining()) {
			throw new BufferUnderflowException();
		}

		ByteBuffer slice = body.slice(body.position(), length);
		body.position(body.position() + length);
		return slice;
	}

	private static final class Frame {
		private final ByteArrayOutputStream out = new ByteArrayOutputStream(64);

		Frame(int id) {
			// Room for the length, filled in by finish().
			u32(0);
			u8(id);
		}

		Frame u8(int value) {
			out.write(value);
			return this;
		}

		Frame u32(int value) {
			out.write(value >>> 24);
			out.write(value >>> 16);
			out.write(value >>> 8);
			out.write(value);
			return this;
		}

		Frame u64(long value) {
			return u32((int) (value >>> 32)).u32((int) value);
		}

		Frame uuid(UUID value) {
			long most = value.getMostSignificantBits();
			long least = value.getLeastSignificantBits();
			return u32((int) (most >>> 32)).u32((int) most).u32((int) (least >>> 32)).u32((int) least);
		}

		Frame string(String value) {
			byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

			if (bytes.length > 0xffff) {
				throw new IllegalArgumentException("string is too long for the wire: " + bytes.length + " bytes");
			}

			out.write(bytes.length >>> 8);
			out.write(bytes.length);
			out.writeBytes(bytes);
			return this;
		}

		Frame blob(byte[] value) {
			u32(value.length);
			out.writeBytes(value);
			return this;
		}

		byte[] finish() {
			byte[] frame = out.toByteArray();
			ByteBuffer.wrap(frame).putInt(frame.length - 4);
			return frame;
		}
	}
}
