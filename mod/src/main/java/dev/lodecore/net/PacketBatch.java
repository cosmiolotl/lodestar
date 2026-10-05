package dev.lodecore.net;

import io.netty.channel.Channel;
import java.util.function.Consumer;
import net.minecraft.network.protocol.Packet;

/** One bounded network task per group; arrays belong to that task after submission. */
public final class PacketBatch {
	private static final int CAPACITY = 128;
	private Packet<?>[] packets;
	private int size;
	private Channel channel;
	private Consumer<Packet<?>> writer;
	private Thread producer;

	public synchronized void add(Channel channel, Packet<?> packet, Consumer<Packet<?>> writer) {
		if (producer != Thread.currentThread()) drain();
		if (packets == null) {
			packets = new Packet<?>[CAPACITY];
			this.channel = channel;
			this.writer = writer;
			producer = Thread.currentThread();
		}
		packets[size++] = packet;
		if (size == CAPACITY) drain();
	}

	public synchronized void drain() {
		if (size == 0) return;
		Packet<?>[] pending = packets;
		int count = size;
		Consumer<Packet<?>> send = writer;
		packets = null;
		size = 0;
		channel.eventLoop().execute(() -> {
			for (int index = 0; index < count; index++) send.accept(pending[index]);
		});
	}
}
