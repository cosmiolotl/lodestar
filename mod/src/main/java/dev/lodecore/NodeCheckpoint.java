package dev.lodecore;

import dev.lodecore.net.Wire;
import dev.lodecore.replication.CheckpointReplicas;
import dev.lodecore.storage.WorldStorage;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Briefly drains replication and captures live state; uploading and committing overlap later ticks. */
final class NodeCheckpoint {

	private static final int TAG = 241;
	private final Node node;
	private final CheckpointReplicas replicas;
	private volatile boolean stopped;
	private boolean stopping;

	NodeCheckpoint(Node node) {
		this.node = node;
		this.replicas = new CheckpointReplicas(node);
	}

	void await(boolean shutdown) {
		ClientPackets.batch(node.server, () -> awaitMessages(shutdown));
	}

	private void awaitMessages(boolean shutdown) {
		if (shutdown) {
			stopping = true;
			for (var player : node.server.getPlayerList().getPlayers()) player.closeContainer();
			node.send(control(3, 0, 0, 0));
		}
		while (node.linkUp && !stopped) {
			Object event;
			try { event = node.inbox.take(); }
			catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Checkpoint wait interrupted", error);
			}
			if (event instanceof Wire.DirectRelay relay && relay.from() == 0 && relay.payload().hasRemaining()
					&& Byte.toUnsignedInt(relay.payload().get(relay.payload().position())) == TAG) {
				ByteBuffer payload = relay.payload().duplicate();
				if (payload.remaining() != 22) throw new IllegalStateException("Malformed checkpoint command");
				payload.get();
				int phase = Byte.toUnsignedInt(payload.get());
				long epoch = payload.getLong();
				long revision = payload.getLong();
				int round = payload.getInt();
				if (phase == 4) {
					stopped = true;
					WorldStorage.seal();
					node.stop();
					return;
				}
				if (phase == 2) {
					if (!stopping) return;
					continue;
				}
				if (phase == 0) drain(round);
				else if (phase == 1) snapshot(epoch, revision, round);
				else throw new IllegalStateException("Unknown checkpoint phase " + phase);
				node.send(control(phase == 1 ? 5 : phase, epoch, revision, round));
			} else if (event instanceof Wire.Tick tick && stopping) {
				// Shutdown joins the next barrier without running another simulation tick.
				node.send(Wire.tickDone(tick.tick()));
			} else if (event instanceof Wire.Inbound message) {
				node.messages.onMessage0(message);
			}
		}
	}

	void shutdown() {
		if (node.linkUp && !stopped) await(true);
	}

	boolean isStopped() { return stopped; }

	private void drain(int round) {
		if (round == 1) replicas.reset();
		node.persistence.prepareEntities();
		node.blocks.flushChanges();
		node.entities.flush();
		node.blockEntities.flush();
		node.custody.flush();
		node.blocks.answerSnapshotRequests();
		node.worldState.flush();
		node.shared.flush();
		node.maps.flush();
		node.raids.flush();
		node.dragonFights.flush();
		replicas.entities(node.persistence.takeReplicaEntities());
		node.persistence.replicaChunks(replicas::blockEntities);
	}

	private void snapshot(long epoch, long revision, int round) {
		var players = node.playerData.snapshot();
		node.persistence.captureChunks();
		for (var level : node.server.getAllLevels()) {
			level.getPoiManager().flushAll();
			((dev.lodecore.mixin.SectionStorageAccessor) level.getPoiManager()).lodecore$storage().synchronize(true).join();
			level.getDataStorage().saveAndJoin();
			level.getChunkSource().chunkMap.synchronize(true).join();
		}
		node.server.getDataStorage().saveAndJoin();
		try { WorldStorage.snapshot(epoch, revision, round, players); }
		catch (IOException e) { throw new IllegalStateException("World revision could not be prepared", e); }
	}

	private static byte[] control(int phase, long epoch, long revision, int round) {
		return Wire.direct(0, ByteBuffer.allocate(22).put((byte) TAG).put((byte) phase).putLong(epoch).putLong(revision).putInt(round).array());
	}
}
