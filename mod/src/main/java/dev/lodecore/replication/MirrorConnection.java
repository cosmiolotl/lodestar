package dev.lodecore.replication;

import io.netty.channel.ChannelFutureListener;
import org.jspecify.annotations.Nullable;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * The connection of a remote player's mirror. The player's client is connected to its home node,
 * which sends it everything it needs, so whatever this node would send it goes nowhere.
 */
public final class MirrorConnection extends Connection {
	public MirrorConnection() {
		super(PacketFlow.SERVERBOUND);
	}

	@Override
	public void send(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
		// A disconnected connection would queue packets until it connects, which it never does.
	}

	@Override
	public void flushChannel() {
	}

	@Override
	public void disconnect(DisconnectionDetails details) {
	}
}
