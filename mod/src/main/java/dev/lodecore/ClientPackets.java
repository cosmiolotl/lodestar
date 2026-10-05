package dev.lodecore;

import net.minecraft.server.MinecraftServer;

/** Replication runs outside vanilla's packet batch; flush once after applying it, not per packet. */
final class ClientPackets {
	private ClientPackets() { }

	static void batch(MinecraftServer server, Runnable action) {
		for (var player : server.getPlayerList().getPlayers()) player.connection.suspendFlushing();
		try { action.run(); }
		finally {
			for (var player : server.getPlayerList().getPlayers()) player.connection.resumeFlushing();
		}
	}
}
