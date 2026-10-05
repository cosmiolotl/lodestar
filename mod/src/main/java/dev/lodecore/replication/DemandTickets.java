package dev.lodecore.replication;

import java.util.HashMap;
import java.util.Map;

import dev.lodecore.net.Wire;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * On the owner of a region, keeps loaded and ticking whatever the other nodes of the cluster have
 * loaded and would tick of it. The owner simulates the region for everyone, so the chunks around
 * a player homed on another node must be alive here too. A node that a region is being handed to
 * holds the same, so that it has all of the region by the time it takes over.
 *
 * <p>Everything here runs on the game thread.
 */
public final class DemandTickets {
	/** Holds a chunk loaded, as the copy other nodes take theirs from. */
	private static final TicketType LOADED = register("replica_loaded", TicketType.FLAG_LOADING);
	/** Ticks a chunk, as a player's simulation distance would. */
	private static final TicketType TICKING = register(
			"replica_ticking",
			TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
	/** Ticket radius that makes the chunk itself fully loaded. */
	private static final int LOADED_RADIUS = 0;
	/** Ticket radius that makes the chunk itself entity ticking, like a force-loaded chunk. */
	private static final int TICKING_RADIUS = 2;

	private final Map<ResourceKey<Level>, Long2ObjectMap<Wire.Demand>> held = new HashMap<>();

	private static TicketType register(String name, int flags) {
		return Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath("lodecore", name), new TicketType(0L, flags));
	}

	/** Registers the ticket types. Must be called while the mod initialises. */
	public static void init() {
		// Loading the class does the work.
	}

	/** Whether another node has a chunk loaded, as far as this node has been told. */
	public boolean isDemanded(ResourceKey<Level> dimension, long chunk) {
		Long2ObjectMap<Wire.Demand> demands = held.get(dimension);
		return demands != null && demands.containsKey(chunk);
	}

	public void set(ServerLevel level, ChunkPos pos, Wire.Demand demand) {
		Long2ObjectMap<Wire.Demand> demands = held.computeIfAbsent(level.dimension(), key -> new Long2ObjectOpenHashMap<>());
		Wire.Demand previous = demand == Wire.Demand.NONE ? demands.remove(pos.pack()) : demands.put(pos.pack(), demand);

		if (previous == demand) {
			return;
		}

		if (previous != null) {
			release(level, pos, previous);
		}

		switch (demand) {
			case LOADED -> level.getChunkSource().addTicketWithRadius(LOADED, pos, LOADED_RADIUS);
			case TICKING -> level.getChunkSource().addTicketWithRadius(TICKING, pos, TICKING_RADIUS);
			case NONE -> {
			}
		}
	}

	/** Lets go of every chunk held for other nodes. */
	public void clear(MinecraftServer server) {
		for (Map.Entry<ResourceKey<Level>, Long2ObjectMap<Wire.Demand>> entry : held.entrySet()) {
			ServerLevel level = server.getLevel(entry.getKey());

			if (level != null) {
				for (Long2ObjectMap.Entry<Wire.Demand> demand : entry.getValue().long2ObjectEntrySet()) {
					release(level, ChunkPos.unpack(demand.getLongKey()), demand.getValue());
				}
			}
		}

		held.clear();
	}

	private static void release(ServerLevel level, ChunkPos pos, Wire.Demand demand) {
		switch (demand) {
			case LOADED -> level.getChunkSource().removeTicketWithRadius(LOADED, pos, LOADED_RADIUS);
			case TICKING -> level.getChunkSource().removeTicketWithRadius(TICKING, pos, TICKING_RADIUS);
			case NONE -> {
			}
		}
	}
}
