package dev.lodecore.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.VarInt;
import net.minecraft.network.Varint21LengthFieldPrepender;

/** Reuses reserved packet headroom while preserving vanilla's framing and size checks. */
public final class PrefixFrameEncoder extends Varint21LengthFieldPrepender {
	@Override
	public void handlerAdded(ChannelHandlerContext context) throws Exception {
		super.handlerAdded(context);
		context.pipeline().addBefore(context.name(), "lodecore_coalescer", new FrameCoalescer());
	}

	@Override
	public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
		if (message instanceof ByteBuf buffer) {
			int length = buffer.readableBytes();
			int header = VarInt.getByteSize(length);
			if (header <= MAX_VARINT21_BYTES && buffer.readerIndex() >= header
					&& !buffer.isReadOnly() && buffer.refCnt() == 1) {
				int start = buffer.readerIndex() - header;
				int index = start;
				while ((length & ~0x7f) != 0) {
					buffer.setByte(index++, (length & 0x7f) | 0x80);
					length >>>= 7;
				}
				buffer.setByte(index, length);
				buffer.readerIndex(start);
				context.write(buffer, promise);
				return;
			}
		}
		super.write(context, message, promise);
	}
}
