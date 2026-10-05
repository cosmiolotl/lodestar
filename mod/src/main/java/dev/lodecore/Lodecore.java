package dev.lodecore;

import dev.lodecore.storage.WorldStorage;
import dev.lodecore.forwarding.Forwarding;
import dev.lodecore.handoff.HandoffLogins;
import dev.lodecore.handoff.Handoffs;
import dev.lodecore.replication.DemandTickets;
import dev.lodecore.replication.Hooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.level.FullChunkStatus;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class Lodecore implements DedicatedServerModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("lodecore");

	/** Null while no server is running. */
	private Node node;

	@Override
	public void onInitializeServer() {
		LodecoreConfig config = LodecoreConfig.load(FabricLoader.getInstance().getConfigDir().resolve("lodecore.properties"));
		Forwarding.register(config.forwardingSecret());
		WorldStorage.initialize(config);
		HandoffLogins.register();
		DemandTickets.init();
		Handoffs.init();

		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> LodecoreCommand.register(dispatcher, () -> node));
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			node = new Node(server, config);
			Hooks.attach(node);
			ChangeHooks.attach(node);
			WorldStorage.attach(node);
		});
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (server.usesAuthentication()) {
				// Logins arrive from the proxy already authenticated, without the encryption
				// handshake online mode expects. Forwarding is what keeps strangers out.
				LOGGER.info("Ignoring online-mode=true: players are authenticated by the proxy");
				server.setUsesAuthentication(false);
			}

			node.start();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (node != null) node.checkpoint.shutdown();
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			if (node != null) {
				try { WorldStorage.flush(); } catch (java.io.IOException e) { LOGGER.error("Flushing world storage", e); }
				Hooks.detach();
				ChangeHooks.attach(null);
				node.stop();
				try { WorldStorage.close(); } catch (java.io.IOException e) { LOGGER.error("Closing world storage", e); }
				node = null;
			}
		});

		ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> {
			if (node != null) {
				node.onPlayerJoin(listener.getPlayer());
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> {
			if (node != null) {
				node.onPlayerLeave(listener.getPlayer());
			}
		});
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
			if (node != null) {
				node.onChunkLoad(level, chunk.getPos());
			}
		});
		ServerChunkEvents.FULL_CHUNK_STATUS_CHANGE.register((level, chunk, oldStatus, newStatus) -> {
			if (node != null && !oldStatus.isOrAfter(FullChunkStatus.FULL) && newStatus.isOrAfter(FullChunkStatus.FULL)) {
				node.onChunkAccessible(level, chunk.getPos());
			}
		});
		ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			if (node != null) {
				node.persistence.save(level, chunk);
				node.onChunkUnload(level, chunk.getPos());
			}
		});
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (node != null) {
				if (entity instanceof net.minecraft.world.entity.npc.InventoryCarrier carrier) {
					((dev.lodecore.storage.EntityInventory) carrier.getInventory()).lodecore$owner(entity);
				}
				node.entities().onEntityLoad(level, entity);
				node.persistence.entityChanged(entity);
			}
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (node != null) {
				node.entities().onEntityUnload(level, entity);
				node.persistence.entityChanged(entity);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (node != null) {
				node.afterTick();
			}
		});
	}
}
