package dev.lodecore;

import dev.lodecore.storage.WorldStorage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.lodecore.net.Wire;
import dev.lodecore.replication.BlockEntityReplication;
import dev.lodecore.replication.Custody;
import dev.lodecore.replication.DragonFightSync;
import dev.lodecore.replication.EntityIds;
import dev.lodecore.replication.EntityReplication;
import dev.lodecore.replication.RaidSync;
import dev.lodecore.replication.WorldState;
import dev.lodecore.shared.GlobalChat;
import dev.lodecore.shared.MapSync;
import dev.lodecore.shared.SharedData;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/** Applies cluster messages on the game thread. */
final class NodeMessages {
	private final Node node;
	NodeMessages(Node node) { this.node = node; }
	void onMessage0(Wire.Inbound message) {
		switch (message) {
			case Wire.DimensionResolved(String name, int id) -> onDimensionResolved(name, id);
			case Wire.RegionOwner owner -> onRegionOwner(owner);
			case Wire.ChunkDemand demand -> {
				ServerLevel level = node.levelsById.get(demand.dimension());

				// Sent to the owner of the chunk's region, and to the node it is being handed to.
				if (level != null) {
					node.demands.set(level, new ChunkPos(demand.chunkX(), demand.chunkZ()), demand.demand());
					node.entities.onRegionOwner(level, Node.regionOf(ChunkPos.pack(demand.chunkX(), demand.chunkZ())));
				}
			}
			case Wire.Relay relay -> {
				ServerLevel level = node.levelsById.get(relay.dimension());

				if (level != null && relay.payload().hasRemaining()) {
					ChunkPos chunk = new ChunkPos(relay.chunkX(), relay.chunkZ());

					switch (kind(relay.payload())) {
						case dev.lodecore.replication.CheckpointReplicas.TAG -> dev.lodecore.replication.CheckpointReplicas.apply(node, relay.from(), level, chunk, relay.payload());
						case EntityReplication.ENTITIES -> node.entities.onRelay(relay.from(), level, chunk, relay.payload());
						case BlockEntityReplication.BLOCK_ENTITIES -> node.blockEntities.onRelay(relay.from(), level, chunk, relay.payload());
						case RaidSync.RAIDS -> node.raids.onRelay(relay.from(), level, relay.payload());
						case DragonFightSync.DRAGON_FIGHT -> node.dragonFights.onRelay(relay.from(), level, relay.payload());
						default -> node.blocks.onRelay(relay.from(), level, chunk, relay.payload());
					}
				}
			}
			case Wire.DirectRelay direct -> {
				if (direct.payload().hasRemaining()) {
					int kind = kind(direct.payload());

					if (EntityReplication.handlesDirect(kind)) {
						node.entities.onDirect(direct.from(), direct.payload());
					} else if (WorldState.handlesDirect(kind)) {
						node.worldState.onPayload(direct.from(), direct.payload());
					} else if (SharedData.handles(kind)) {
						node.shared.onPayload(direct.from(), direct.payload());
					} else if (MapSync.handles(kind)) {
						node.maps.onPayload(direct.from(), direct.payload());
					} else if (RaidSync.handlesDirect(kind)) {
						node.raids.onDirect(direct.from(), direct.payload());
					} else if (DragonFightSync.handlesDirect(kind)) {
						node.dragonFights.onDirect(direct.from(), direct.payload());
					} else {
						node.blocks.onDirect(direct.from(), direct.payload());
					}
				}
			}
			case Wire.Master(int scope, int master) -> {
				node.globalData.beforeMaster(scope);
				node.worldState.onMaster(scope, master);

				if (scope == Wire.CLUSTER_SCOPE) {
					WorldStorage.master(master == node.nodeId());
					node.shared.onMaster(master);
					node.maps.onMaster(master);
				}
				node.globalData.afterMaster(scope, master);
			}
			case Wire.BroadcastRelay broadcast -> {
				if (broadcast.payload().hasRemaining()) {
					int kind = kind(broadcast.payload());

					if (SharedData.handles(kind)) {
						node.shared.onPayload(broadcast.from(), broadcast.payload());
					} else if (MapSync.handles(kind)) {
						node.maps.onPayload(broadcast.from(), broadcast.payload());
					} else {
						node.worldState.onPayload(broadcast.from(), broadcast.payload());
					}
				}
			}
			case Wire.ClaimRequest request -> node.custody.onClaimRequest(request);
			case Wire.ClaimResult result -> node.custody.onClaimResult(result);
			case Wire.Custody holder -> {
                node.custody.onCustody(holder);
                var level = node.level(holder.dimension());
                if (level != null) node.entities.authorityChanged(level, holder.object());
            }
			case Wire.Released released -> node.custody.onReleased(released);
			case Wire.PlayerData data -> node.playerData.onPlayerData(data);
			case Wire.IdBlock(int block) -> {
				Lodecore.LOGGER.info("This node hands out entity ids from block {}", block);

				if (node.entityIds.onBlock(block)) {
					takeIdsFromOwnBlock();
				}
			}
			case Wire.AnnounceRelay announcement -> {
				if (announcement.payload().hasRemaining() && GlobalChat.handles(kind(announcement.payload()))) {
					node.chat.onAnnounce(announcement.from(), announcement.payload());
				}
			}
			case Wire.CounterBlock(int counter, int block) -> {
				switch (counter) {
					case Wire.COUNTER_MAP_IDS -> node.maps.onIdBlock(block);
					case Wire.COUNTER_RAID_IDS -> node.raids.onIdBlock(block);
					default -> Lodecore.LOGGER.warn("lodestar handed out a block of unknown counter {}", counter);
				}
			}
			case Wire.HandoffPrepare prepare -> node.handoffs.onPrepare(prepare);
			case Wire.HandoffCut cut -> node.handoffs.onCut(cut);
			case Wire.HandoffArrive arrive -> node.handoffs.onArrive(arrive);
			case Wire.HandoffCancel cancel -> node.handoffs.onCancel(cancel);
			case Wire.PlayerJoined(int home, UUID uuid, String name) -> node.homes.put(uuid, home);
			case Wire.PlayerLeft(int home, UUID uuid) -> {
				// A leave can arrive after the player has already joined another node.
				if (node.homes.remove(uuid, home)) {
					node.entities.onPlayerLeft(uuid);
				}
			}
			default -> {
			}
		}
	}

	private static int kind(ByteBuffer payload) {
		return Byte.toUnsignedInt(payload.get(payload.position()));
	}

	void onDimensionResolved(String name, int id) {
		for (ServerLevel level : node.server.getAllLevels()) {
			ResourceKey<Level> dimension = level.dimension();

			if (dimension.identifier().toString().equals(name)) {
				node.dimensionIds.put(dimension, id);
				node.levelsById.put(id, level);

				// Chunk events that fired before the id was known could not be reported.
				for (long chunk : node.loadedChunks.getOrDefault(dimension, LongSet.of())) {
					node.send(Wire.subscribe(id, ChunkPos.getX(chunk), ChunkPos.getZ(chunk)));
				}
			}
		}
	}

	void onRegionOwner(Wire.RegionOwner message) {
		ServerLevel level = node.levelsById.get(message.dimension());

		if (level == null) {
			return;
		}

		long region = ChunkPos.pack(message.regionX(), message.regionZ());
		Long2IntMap owners = node.regionOwners.computeIfAbsent(level.dimension(), key -> new Long2IntOpenHashMap());
		int owner = message.owner();
		WorldStorage.owner(level.dimension().identifier().toString(), message.regionX(), message.regionZ(), owner);
		int previous = owner == Node.NO_NODE ? owners.remove(region) : owners.put(region, owner);

		if (previous == owner) {
			return;
		}

		if (owner != Node.NO_NODE) {
			Lodecore.LOGGER.info(
					"Region {} {} of {} is simulated by {}",
					message.regionX(), message.regionZ(), level.dimension().identifier(), owner == node.nodeId ? "this node" : "node #" + owner);
		}

		LongList chunks = new LongArrayList();

		for (long chunk : node.loadedChunks.getOrDefault(level.dimension(), LongSet.of())) {
			if (Node.regionOf(chunk) == region) {
				chunks.add(chunk);
				if (owner == node.nodeId()) node.persistence.loaded(level, chunk);
			}
		}

		node.blocks.onRegionOwner(level, chunks, owner);
		node.entities.onRegionOwner(level, region);
	}

	/**
	 * Gives the entities made before this node had a block of ids of its own ids from its first
	 * block. They had ids every node hands out before it has a block, which their mirrors could not
	 * have. No player can have arrived yet to have been shown them.
	 */
	void takeIdsFromOwnBlock() {
		int moved = 0;

		for (ServerLevel level : node.server.getAllLevels()) {
			List<Entity> entities = new ArrayList<>();
			level.getAllEntities().forEach(entities::add);

			for (Entity entity : entities) {
				if (!(entity instanceof Player) && EntityIds.isShared(entity.getId()) && EntityIds.reassign(level, entity, node.entityIds.next())) {
					moved++;
				}
			}
		}

		if (moved > 0) {
			Lodecore.LOGGER.info("Gave {} entities ids from this node's own block", moved);
		}
	}

}
