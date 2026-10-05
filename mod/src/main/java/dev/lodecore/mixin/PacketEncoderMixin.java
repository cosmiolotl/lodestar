package dev.lodecore.mixin;

import dev.lodecore.net.EncodedBroadcast;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketEncoder.class)
abstract class PacketEncoderMixin {
	@Redirect(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/network/codec/StreamCodec;encode(Ljava/lang/Object;Ljava/lang/Object;)V"))
	private void lodecore$reuseBroadcast(StreamCodec<ByteBuf, Packet<?>> codec, Object output, Object packet) {
		if (packet instanceof EncodedBroadcast broadcast) broadcast.encode(codec, (ByteBuf) output);
		else codec.encode((ByteBuf) output, (Packet<?>) packet);
	}

	@Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V", at = @At("HEAD"))
	private void lodecore$reserveHeaders(ChannelHandlerContext context, Packet<?> packet, ByteBuf output, CallbackInfo ci) {
		// Three length bytes and one uncompressed marker. Unread headroom stays off
		// the wire until the framing handlers fill it; normal codecs are unchanged.
		output.writeZero(4);
		output.readerIndex(output.writerIndex());
	}
}
