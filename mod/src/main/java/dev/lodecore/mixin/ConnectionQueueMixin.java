package dev.lodecore.mixin;

import dev.lodecore.net.BatchedConnection;
import dev.lodecore.net.PacketBatch;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import java.util.Queue;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
abstract class ConnectionQueueMixin implements BatchedConnection {
	@Shadow @Final private Queue<Consumer<Connection>> pendingActions;
	@Shadow private Channel channel;
	@Shadow private int sentPackets;
	@Shadow protected abstract void doSendPacket(Packet<?> packet, ChannelFutureListener listener, boolean flush);
	@Unique private final PacketBatch lodecore$batch = new PacketBatch();
	@Unique private final Consumer<Packet<?>> lodecore$writer = packet -> doSendPacket(packet, null, false);

	@Inject(method = "flushQueue", at = @At("HEAD"), cancellable = true)
	private void lodecore$skipEmptyQueue(CallbackInfo ci) {
		if (pendingActions.isEmpty()) ci.cancel();
	}

	@Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
	private void lodecore$batchPacket(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
		if (flush || listener != null || packet.isTerminal() || channel.eventLoop().inEventLoop()) {
			lodecore$drainPackets();
			return;
		}
		sentPackets++;
		lodecore$batch.add(channel, packet, lodecore$writer);
		ci.cancel();
	}

	@Override public void lodecore$drainPackets() { lodecore$batch.drain(); }

	// Pipeline changes and connection actions must stay behind every preceding packet.
	@Inject(method = {"flush", "runOnceConnected", "setupOutboundProtocol", "setupInboundProtocol",
			"setupCompression", "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", "tick"}, at = @At("HEAD"))
	private void lodecore$packetBoundary(CallbackInfo ci) { lodecore$drainPackets(); }
}
