package dev.lodecore;

import dev.lodecore.net.Wire;

/** Flushes a simulation tick before the coordinator advances the clock. */
final class NodeTicks {
	private final Node node;
	NodeTicks(Node node) { this.node = node; }
	void afterTick() {
		ClientPackets.batch(node.server, this::flushTick);
		// Last, so lodestar has everything this tick produced before it hears the tick is over.
		if (node.clusterTick >= 0) {
			node.send(Wire.tickDone(node.clusterTick));
			node.checkpoint.await(false);
			node.clusterTick = -1;
		}
	}

	private void flushTick() {
		if (node.connected) {
			for (var level : node.server.getAllLevels()) {
				((dev.lodecore.replication.WaypointUpdates) level.getWaypointManager()).lodecore$flushWaypoints();
			}
			node.playerData.tick();
			node.persistence.tick();
			// Block changes next: they carry the block entities placed with them.
			node.blocks.flushChanges();
			node.entities.flush();
			node.blockEntities.flush();
			// After this tick's changes, so that what an object's holder is handed is up to date.
			node.custody.flush();
			// After the changes, so that a snapshot never predates a change its receiver already has.
			node.blocks.answerSnapshotRequests();
			node.worldState.flush();
			node.shared.flush();
			node.maps.flush();
			// After the entities, so that the raiders and dragon these speak of have gone out.
			node.raids.flush();
			node.dragonFights.flush();
			node.chunks.reportPlayers();
			// After everything this tick did with a player has gone out.
			node.handoffs.tick();

			if (node.entityIds.shouldAsk()) {
				node.entityIds.onAsked();
				node.send(Wire.idBlockRequest());
			}

			if (node.server.getTickCount() % Node.REPORT_INTERVAL_TICKS == 0) {
				node.chunks.reportTickingChunks();
				int msptMicros = (int) Math.min(node.server.getAverageTickTimeNanos() / 1000, Integer.MAX_VALUE);
				node.send(Wire.heartbeat(msptMicros, node.server.getPlayerCount()));
			}
		}

	}

}
