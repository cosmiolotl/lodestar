package dev.lodecore.handoff;

import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;

/**
 * Asks the proxy, of every login, whether it moves a player here from another node. If it does,
 * the proxy answers with the token lodestar gave out for the move, and the connection waits here,
 * logged in, until the player arrives.
 */
public final class HandoffLogins {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/handoff");
	private static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("lodecore", "handoff");

	private HandoffLogins() {
	}

	public static void register() {
		ServerLoginConnectionEvents.QUERY_START.register((listener, server, sender, synchronizer) -> {
			synchronizer.waitFor(((HandoffLogin) listener).lodecore$handoffAnswered());
			sender.sendPacket(CHANNEL, new FriendlyByteBuf(Unpooled.buffer()));
		});

		ServerLoginNetworking.registerGlobalReceiver(CHANNEL, (server, listener, understood, buf, synchronizer, sender) -> {
			HandoffLogin login = (HandoffLogin) listener;

			try {
				if (understood) {
					login.lodecore$setHandoffToken(buf.readLong());
				}
			} catch (IndexOutOfBoundsException e) {
				LOGGER.warn("The proxy sent a malformed handoff token");
			} finally {
				login.lodecore$handoffAnswered().complete(null);
			}
		});
	}
}
