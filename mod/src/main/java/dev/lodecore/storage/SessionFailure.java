package dev.lodecore.storage;

import dev.lodecore.Lodecore;
import net.minecraft.server.MinecraftServer;

/** Bounds shutdown after losing authority, even if vanilla storage futures cannot finish. */
public final class SessionFailure {
	private SessionFailure() { }

	public static void stop(MinecraftServer server) {
		Lodecore.LOGGER.error("Lost the authoritative world session; stopping this worker at the last committed revision");
		Thread.ofPlatform().daemon(true).name("lodecore-session-fence").start(() -> {
			try { server.getRunningThread().join(10_000); }
			catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
			if (server.getRunningThread().isAlive()) {
				// Local cache teardown cannot commit anything after a session failure. Waiting
				// forever on failed chunk futures would leave a fenced worker occupying its port.
				Lodecore.LOGGER.error("Worker shutdown stalled after session loss; terminating the process");
				Runtime.getRuntime().halt(1);
			}
		});
	}
}
