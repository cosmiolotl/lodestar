package dev.lodecore.replication;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import dev.lodecore.Node;
import dev.lodecore.mixin.PlayerListAccessor;
import dev.lodecore.mixin.ServerGamePacketListenerImplInvoker;
import dev.lodecore.mixin.ServerWaypointManagerAccessor;
import org.jspecify.annotations.Nullable;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;

/**
 * The players homed on other nodes that stand where this node has chunks loaded.
 *
 * <p>Each is mirrored by a {@link ServerPlayer} of its own: that is what the game shows other
 * players as, and what its mobs look for when they look for a player. It is in the level but not
 * in this server's player list, has a connection that goes nowhere, loads no chunks here (unless
 * this node owns where it stands, when it counts as a player for spawning, as it would on a single
 * server), and is never ticked: it moves when its home node says so.
 *
 * <p>Everything here runs on the game thread.
 */
final class RemotePlayers {
	private final Node node;
	private final Map<UUID, ServerPlayer> mirrors = new HashMap<>();
	/** Mirrors whose chunk was unloaded here, to be let go of outside the game's entity bookkeeping. */
	private final List<ServerPlayer> unloaded = new ArrayList<>();

	RemotePlayers(Node node) {
		this.node = node;
	}

	boolean isMirror(ServerPlayer player) {
		return mirrors.get(player.getUUID()) == player;
	}

	@Nullable ServerPlayer get(UUID uuid) {
		return mirrors.get(uuid);
	}

	Collection<ServerPlayer> all() {
		return List.copyOf(mirrors.values());
	}

	/** A mirror becomes a player homed here. Nobody is told: it is the same player, in the same place. */
	void forget(ServerPlayer mirror) {
		mirrors.remove(mirror.getUUID(), mirror);
	}

	/** A player homed here moved to another node, and stays here as the mirror of a remote player. */
	void adopt(ServerPlayer player) {
		mirrors.put(player.getUUID(), player);
		((ServerWaypointManagerAccessor) player.level().getWaypointManager()).lodecore$players().remove(player);
	}

	/**
	 * Moves a remote player's mirror, making it first if need be. The mirror has the id the
	 * player's home node gave the player, as far as no other entity here has it.
	 *
	 * @return the mirror, or null if the player turns out to be homed here
	 */
	@Nullable ServerPlayer update(ServerLevel level, GameProfile profile, EntityWire.Move move, int id) {
		ServerPlayer mirror = mirrors.get(profile.id());

		// Mirrors do not change dimension; the player's new dimension makes a new one.
		if (mirror != null && mirror.level() != level) {
			remove(mirror);
			mirror = null;
		}

		if (mirror == null) {
			if (node.server().getPlayerList().getPlayer(profile.id()) != null) {
				return null;
			}

			mirror = create(level, profile, move, id);
		} else {
			EntityIds.reassign(level, mirror, id);
		}

		EntityPresentation.applyMove(level, mirror, move);
		return mirror;
	}

	ServerPlayer create(ServerLevel level, GameProfile profile, EntityWire.Move at, int id) {
		MinecraftServer server = node.server();
		ServerPlayer mirror = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
		// The player's stats and advancements are its home node's business. Making a player loads
		// them from this node's save, so forget them again, and keep the loaded advancements from
		// following what the mirror does.
		mirror.getAdvancements().clearTriggers();
		PlayerListAccessor players = (PlayerListAccessor) server.getPlayerList();
		players.lodecore$stats().remove(profile.id());
		players.lodecore$advancements().remove(profile.id());
		ServerGamePacketListenerImpl connection = new ServerGamePacketListenerImpl(server, new MirrorConnection(), mirror, CommonListenerCookie.createInitial(profile, false));
		// A player is spared damage until its client has loaded the world, or a few ticks have
		// passed. A mirror's client is its home node's business, and a mirror does not tick.
		((ServerGamePacketListenerImplInvoker) connection).lodecore$markClientLoaded();
		mirror.snapTo(at.position(), at.yRot(), at.xRot());

		if (EntityIds.isFree(level, id)) {
			mirror.setId(id);
		}

		mirrors.put(profile.id(), mirror);
		// A client must know a player before it is shown the player's entity.
		server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(mirror)));
		level.addNewPlayer(mirror);
		return mirror;
	}

	void remove(ServerPlayer mirror) {
		if (isMirror(mirror)) {
			((ServerLevel) mirror.level()).removePlayerImmediately(mirror, Entity.RemovalReason.UNLOADED_WITH_PLAYER);
			// Unloading the entity has forgotten it; see onUnloaded.
		}
	}

	void remove(UUID uuid) {
		ServerPlayer mirror = mirrors.get(uuid);

		if (mirror != null) {
			remove(mirror);
		}
	}

	void removeAll() {
		for (ServerPlayer mirror : List.copyOf(mirrors.values())) {
			remove(mirror);
		}
	}

	/** The game stopped tracking a player, because it was removed or its chunk unloaded. */
	void onUnloaded(ServerPlayer player) {
		if (mirrors.remove(player.getUUID(), player)) {
			node.server().getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));

			if (!player.isRemoved()) {
				unloaded.add(player);
			}
		}
	}

	/** Lets go of the mirrors whose chunks were unloaded. */
	void flush() {
		for (ServerPlayer player : unloaded) {
			if (!player.isRemoved()) {
				player.setRemoved(Entity.RemovalReason.UNLOADED_WITH_PLAYER);
			}
		}

		unloaded.clear();
	}

	/** Whether the mirrors in a region count as players for chunk loading may have changed. */
	void onRegionOwner(ServerLevel level, long region) {
		for (ServerPlayer mirror : mirrors.values()) {
			if (mirror.level() == level && Node.regionOf(mirror.chunkPosition().pack()) == region) {
				level.getChunkSource().move(mirror);
			}
		}
	}

	/** Tells a player who just joined this node about the remote players it may be shown. */
	void introduceTo(ServerPlayer player) {
		if (!mirrors.isEmpty()) {
			player.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(mirrors.values()));
		}
	}
}
