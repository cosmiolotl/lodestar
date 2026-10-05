package dev.lodecore.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;

/** One encoding per broadcast of an immutable, registry-independent vanilla play packet. */
public final class EncodedBroadcast implements Packet<ClientGamePacketListener> {
	private final Packet<? super ClientGamePacketListener> packet;
	private volatile byte[] encoded;

	private EncodedBroadcast(Packet<? super ClientGamePacketListener> packet) { this.packet = packet; }

	public static Packet<? super ClientGamePacketListener> wrap(Packet<? super ClientGamePacketListener> packet) {
		if (packet instanceof ClientboundMoveEntityPacket || packet instanceof ClientboundRotateHeadPacket
				|| packet instanceof ClientboundSetEntityMotionPacket || packet instanceof ClientboundEntityPositionSyncPacket
				|| packet instanceof ClientboundAnimatePacket || packet instanceof ClientboundEntityEventPacket) {
			return new EncodedBroadcast(packet);
		}
		return packet;
	}

	public void encode(StreamCodec<ByteBuf, Packet<?>> codec, ByteBuf output) {
		byte[] bytes = encoded;
		if (bytes != null) {
			output.writeBytes(bytes);
			return;
		}
		// Different viewers' event loops can reach a new broadcast concurrently.
		synchronized (this) {
			if (encoded != null) {
				output.writeBytes(encoded);
				return;
			}
			int start = output.writerIndex();
			codec.encode(output, packet);
			byte[] result = new byte[output.writerIndex() - start];
			output.getBytes(start, result);
			encoded = result;
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public PacketType<? extends Packet<ClientGamePacketListener>> type() {
		// ClientGamePacketListener also accepts the common clientbound packet types.
		return (PacketType<? extends Packet<ClientGamePacketListener>>) packet.type();
	}

	@Override public void handle(ClientGamePacketListener listener) { packet.handle(listener); }
	@Override public boolean isSkippable() { return packet.isSkippable(); }
}
