package dev.lodecore.forwarding;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.net.InetAddresses;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;

/**
 * Asks the proxy who each connecting player is.
 *
 * <p>A node does no authentication of its own, so this is the only thing standing between it and
 * anyone who can reach its port. A login goes ahead only after the proxy has answered with an
 * identity signed with the cluster's forwarding secret; {@code ServerLoginPacketListenerImplMixin}
 * refuses everything else.
 */
public final class Forwarding {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/forwarding");
	private static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("velocity", "player_info");
	private static final int VERSION = 1;

	public static final Component NOT_THROUGH_PROXY = Component.literal("This server can only be joined through its proxy.");

	private Forwarding() {
	}

	public static void register(String forwardingSecret) {
		byte[] secret = forwardingSecret.getBytes(StandardCharsets.UTF_8);

		ServerLoginConnectionEvents.QUERY_START.register((listener, server, sender, synchronizer) -> {
			// Hold the login until the answer has been dealt with. Fabric stops waiting on a query
			// as soon as its answer arrives, which is before the handler below has run.
			synchronizer.waitFor(((ForwardedLogin) listener).lodecore$answered());

			FriendlyByteBuf request = new FriendlyByteBuf(Unpooled.buffer());
			request.writeByte(VERSION);
			sender.sendPacket(CHANNEL, request);
		});

		ServerLoginNetworking.registerGlobalReceiver(CHANNEL, (server, listener, understood, buf, synchronizer, sender) -> {
			ForwardedLogin login = (ForwardedLogin) listener;

			try {
				if (!understood) {
					listener.disconnect(NOT_THROUGH_PROXY);
					return;
				}

				byte[] data = new byte[buf.readableBytes()];
				buf.readBytes(data);
				ForwardedIdentity identity = ForwardedIdentity.verify(data, secret);

				ImmutableMultimap.Builder<String, Property> properties = ImmutableMultimap.builder();

				for (ForwardedIdentity.Property property : identity.properties()) {
					properties.put(property.name(), new Property(property.name(), property.value(), property.signature()));
				}

				GameProfile profile = new GameProfile(identity.id(), identity.name(), new PropertyMap(properties.build()));
				// The port is the proxy's; only the address is meaningful.
				login.lodecore$accept(profile, new InetSocketAddress(InetAddresses.forString(identity.address()), 0));
			} catch (ForwardedIdentity.InvalidIdentityException | IllegalArgumentException e) {
				LOGGER.warn("Refusing a login with a bad forwarded identity: {}", e.getMessage());
				listener.disconnect(NOT_THROUGH_PROXY);
			} finally {
				login.lodecore$answered().complete(null);
			}
		});
	}
}
