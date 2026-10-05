package dev.lodecore.forwarding;

import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;

import com.mojang.authlib.GameProfile;

/** Implemented by the login listener through {@code ServerLoginPacketListenerImplMixin}. */
public interface ForwardedLogin {
	/** Completes once the proxy's answer has been dealt with, whatever the outcome. */
	CompletableFuture<Void> lodecore$answered();

	/** Replaces the identity the client claimed with the one the proxy vouches for. */
	void lodecore$accept(GameProfile profile, SocketAddress address);
}
