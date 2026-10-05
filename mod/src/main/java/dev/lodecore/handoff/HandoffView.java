package dev.lodecore.handoff;

import dev.lodecore.Node;
import dev.lodecore.mixin.ChunkMapAccessor;
import dev.lodecore.mixin.PlayerChunkSenderAccessor;
import dev.lodecore.mixin.ServerEntityAccessor;
import dev.lodecore.mixin.TrackedEntityAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

final class HandoffView {
	static void restoreView(Node node, ServerPlayer player, CompoundTag tag) {
		ServerLevel level = player.level();
		ServerGamePacketListenerImpl listener = player.connection;

		int[] view = tag.getIntArray("ChunkView").orElse(new int[0]);
		player.setChunkTrackingView(view.length == 3 ? ChunkTrackingView.of(new ChunkPos(view[0], view[1]), view[2]) : ChunkTrackingView.EMPTY);
		PlayerChunkSenderAccessor chunks = (PlayerChunkSenderAccessor) listener.chunkSender;
		chunks.lodecore$pendingChunks().clear();

		for (long pending : tag.getLongArray("PendingChunks").orElse(new long[0])) {
			LevelChunk chunk = level.getChunkSource().chunkMap.getChunkToSend(pending);

			// One not loaded here yet is sent once it is, as the client is taken to want it.
			if (chunk != null) {
				listener.chunkSender.markChunkPendingToSend(chunk);
			}
		}

		chunks.lodecore$setDesiredChunksPerTick(tag.getFloatOr("ChunksPerTick", 9.0F));
		chunks.lodecore$setBatchQuota(tag.getFloatOr("BatchQuota", 0.0F));
		chunks.lodecore$setUnacknowledgedBatches(tag.getIntOr("UnacknowledgedBatches", 0));
		chunks.lodecore$setMaxUnacknowledgedBatches(tag.getIntOr("MaxUnacknowledgedBatches", 1));

		// The player list comes first: a client must know a player before it is shown them.
		Set<UUID> listed = uuids(tag.getLongArray("Listed").orElse(new long[0]));
		List<ServerPlayer> here = new ArrayList<>(node.server().getPlayerList().getPlayers());
		here.addAll(node.entities().remotePlayerMirrors());
		Set<UUID> hereIds = new HashSet<>();
		List<ServerPlayer> introduce = new ArrayList<>();

		for (ServerPlayer other : here) {
			hereIds.add(other.getUUID());

			if (!listed.contains(other.getUUID())) {
				introduce.add(other);
			}
		}

		List<UUID> forget = listed.stream().filter(uuid -> !hereIds.contains(uuid)).toList();

		if (!forget.isEmpty()) {
			listener.send(new ClientboundPlayerInfoRemovePacket(forget));
		}

		if (!introduce.isEmpty()) {
			listener.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(introduce));
		}

		// Entities the client has, which have the same id here, it goes on seeing; the rest it
		// is told are gone. The game's tracking takes it from there.
		Int2ObjectMap<?> tracked = ((ChunkMapAccessor) level.getChunkSource().chunkMap).lodecore$entityMap();

		for (Object entity : tracked.values()) {
			((TrackedEntityAccessor) entity).lodecore$seenBy().remove(listener);
		}

		int[] ids = tag.getIntArray("SeenIds").orElse(new int[0]);
		long[] seenUuids = tag.getLongArray("SeenUuids").orElse(new long[0]);
		IntList gone = new IntArrayList();

		for (int i = 0; i < ids.length && 2 * i + 1 < seenUuids.length; i++) {
			int id = ids[i];
			Entity entity = level.getEntity(new UUID(seenUuids[2 * i], seenUuids[2 * i + 1]));
			Object trackedEntity = entity == null || entity == player || entity.getId() != id ? null : tracked.get(id);

			if (trackedEntity == null) {
				gone.add(id);
				continue;
			}

			TrackedEntityAccessor seen = (TrackedEntityAccessor) trackedEntity;
			seen.lodecore$seenBy().add(listener);
			entity.startSeenByPlayer(player);
			// Positions are sent as steps from where this node last said the entity was, which
			// is not quite where the last node said it was.
			Vec3 base = ((ServerEntityAccessor) seen.lodecore$serverEntity()).lodecore$positionCodec().getBase();
			listener.send(new ClientboundEntityPositionSyncPacket(id, PositionPath.of(base), entity.getYRot(), entity.getXRot(), entity.onGround()));
		}

		if (!gone.isEmpty()) {
			listener.send(new ClientboundRemoveEntitiesPacket(gone));
		}
	}
	private static Set<UUID> uuids(long[] bits) {
		Set<UUID> uuids = new HashSet<>();

		for (int i = 0; i + 1 < bits.length; i += 2) {
			uuids.add(new UUID(bits[i], bits[i + 1]));
		}

		return uuids;
	}
}
