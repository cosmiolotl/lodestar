package dev.lodecore.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.CompressionEncoder;

/** Small packets only need a zero compression marker, not another buffer and copy. */
public final class PrefixCompressionEncoder extends CompressionEncoder {
	public PrefixCompressionEncoder(int threshold) { super(threshold); }

	@Override
	public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
		if (message instanceof ByteBuf buffer && buffer.readableBytes() < getThreshold()
				&& buffer.readableBytes() <= 1 << 23 && buffer.readerIndex() > 0
				&& !buffer.isReadOnly() && buffer.refCnt() == 1) {
			int start = buffer.readerIndex() - 1;
			buffer.setByte(start, 0);
			buffer.readerIndex(start);
			// Ownership transfers downstream exactly once, as in MessageToByteEncoder.
			context.write(buffer, promise);
			return;
		}
		super.write(context, message, promise);
	}
}
