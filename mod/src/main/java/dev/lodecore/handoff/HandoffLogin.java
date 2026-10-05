package dev.lodecore.handoff;

import java.util.concurrent.CompletableFuture;

/** Implemented by the login listener through {@code ServerLoginPacketListenerImplMixin}. */
public interface HandoffLogin {
	/** Completes once the proxy has said whether this login moves a player here. */
	CompletableFuture<Void> lodecore$handoffAnswered();

	/** The proxy presented a token: this login moves a player here from another node. */
	void lodecore$setHandoffToken(long token);
}
