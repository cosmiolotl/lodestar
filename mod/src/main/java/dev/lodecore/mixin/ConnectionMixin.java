package dev.lodecore.mixin;

import dev.lodecore.handoff.HandoffConnection;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;

/** Counts what comes in, and lets a player's connection be cut loose when they move away. */
@Mixin(Connection.class)
abstract class ConnectionMixin implements HandoffConnection {
	@Shadow
	private @Nullable Channel channel;

	@Shadow
	private boolean disconnectionHandled;

	@Shadow
	private volatile @Nullable PacketListener packetListener;

	/** Written by the connection's own thread only. */
	@Unique
	private volatile long lodecore$received;

	@Unique
	private volatile boolean lodecore$detached;

	@Override
	public long lodecore$received() {
		return this.lodecore$received;
	}

	@Override
	public void lodecore$detach() {
		((dev.lodecore.net.BatchedConnection) this).lodecore$drainPackets();
		this.lodecore$detached = true;
		// Whatever is told of the connection closing, such as the Fabric API's disconnect event,
		// is told through its listener, and the player it served has not left.
		this.packetListener = null;
	}

	@Override
	public void lodecore$hangUpWhenSent() {
		Channel channel = this.channel;

		if (channel == null) {
			return;
		}

		// Flush buffered frames before placing the close fence past the encoders.
		channel.eventLoop().execute(() -> {
			channel.flush();
			ChannelHandlerContext first = channel.pipeline().firstContext();
			ChannelFuture written = first != null ? first.writeAndFlush(Unpooled.EMPTY_BUFFER) : channel.writeAndFlush(Unpooled.EMPTY_BUFFER);
			written.addListener(ChannelFutureListener.CLOSE);
		});
	}

	@Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void lodecore$ignoreOnceDetached(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
		if (this.lodecore$detached) {
			ci.cancel();
		}
	}

	@Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("TAIL"))
	private void lodecore$countReceived(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
		this.lodecore$received++;
	}

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void lodecore$tickNothingOnceDetached(CallbackInfo ci) {
		if (this.lodecore$detached) {
			ci.cancel();
		}
	}

	@Inject(method = "handleDisconnection", at = @At("HEAD"), cancellable = true)
	private void lodecore$hangUpQuietly(CallbackInfo ci) {
		if (this.lodecore$detached) {
			this.disconnectionHandled = true;
			ci.cancel();
		}
	}

	@Inject(method = "exceptionCaught", at = @At("HEAD"), cancellable = true)
	private void lodecore$failQuietly(ChannelHandlerContext context, Throwable cause, CallbackInfo ci) {
		if (this.lodecore$detached) {
			context.close();
			ci.cancel();
		}
	}

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), cancellable = true)
	private void lodecore$sendNothingOnceDetached(CallbackInfo ci) {
		if (this.lodecore$detached) {
			ci.cancel();
		}
	}
}
