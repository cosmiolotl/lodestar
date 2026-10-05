package dev.lodecore.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

import dev.lodecore.Node;
import dev.lodecore.net.Wire;
import dev.lodecore.shared.MapSync;
import dev.lodecore.shared.SharedData;

/** Replays centrally staged global state before this node first adopts a scope's master. */
public final class GlobalWorldData {
	private final Node node;
	private final Set<Integer> restored = new HashSet<>();

	public GlobalWorldData(Node node) { this.node = node; }

	public void flush() {
		for (var player : node.server().getPlayerList().getPlayers()) node.playerData().onSaved(player);
		node.worldState().flush();
		node.shared().flush();
		node.maps().flush();
		try { WorldStorage.flush(); } catch (IOException e) { throw new IllegalStateException("Could not flush the global world", e); }
	}

	public void beforeMaster(int scope) {
		if (!restored.add(scope)) return;
		String name = scope == Wire.CLUSTER_SCOPE ? "cluster" : node.level(scope).dimension().identifier().toString();
		try {
			long offset = 0;
			while (true) {
				byte[] page = WorldStorage.journal(name, offset);
				if (page.length == 0) break;
				offset += page.length;
				ByteBuffer input = ByteBuffer.wrap(page);
				while (input.hasRemaining()) {
					int length = input.getInt();
					ByteBuffer payload = input.slice(input.position(), length);
					input.position(input.position() + length);
					int kind = Byte.toUnsignedInt(payload.get(0));
					// Dimension numbers are connection-local; the store uses dimension names.
					if (kind == 10) payload.putInt(1, scope);
					if (SharedData.handles(kind)) node.shared().onPayload(0, payload);
					else if (MapSync.handles(kind)) node.maps().onPayload(0, payload);
					else node.worldState().onPayload(0, payload);
				}
			}
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("Cannot restore authoritative world state for " + name, e);
		}
	}

	public void afterMaster(int scope, int master) {
		if (master != node.nodeId()) return;
		// Persist an initial baseline, including unchanged local data during migration.
		node.worldState().onPayload(0, ByteBuffer.allocate(5).put((byte) 11).putInt(scope).flip());
		if (scope == Wire.CLUSTER_SCOPE) node.shared().onPayload(0, ByteBuffer.wrap(new byte[] {14}));
	}
}
