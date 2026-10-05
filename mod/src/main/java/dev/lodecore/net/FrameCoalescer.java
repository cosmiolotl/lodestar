package dev.lodecore.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

/** Combines small, already framed writes until the existing flush boundary. */
public final class FrameCoalescer extends ChannelOutboundHandlerAdapter {
	private static final int CAPACITY = 16 * 1024;
	private ByteBuf pending;

	@Override
	public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
		// Listener-bearing writes retain their own completion and failure signal.
		if (!(message instanceof ByteBuf buffer) || !promise.isVoid() || buffer.readableBytes() > CAPACITY) {
			drain(context);
			context.write(message, promise);
			return;
		}
		try {
			if (pending != null && pending.writableBytes() < buffer.readableBytes()) drain(context);
			if (pending == null) pending = context.alloc().ioBuffer(CAPACITY, CAPACITY);
			pending.writeBytes(buffer);
		} finally {
			buffer.release();
		}
	}

	private void drain(ChannelHandlerContext context) {
		if (pending == null) return;
		ByteBuf buffer = pending;
		pending = null;
		context.write(buffer, context.voidPromise());
	}

	@Override
	public void flush(ChannelHandlerContext context) {
		drain(context);
		context.flush();
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext context) {
		if (pending == null) return;
		if (context.channel().isActive()) {
			drain(context);
			context.flush();
		} else {
			pending.release();
			pending = null;
		}
	}
}
