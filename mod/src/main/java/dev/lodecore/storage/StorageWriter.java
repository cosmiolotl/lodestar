package dev.lodecore.storage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Map;

import dev.lodecore.LodecoreConfig;
import dev.lodecore.net.Wire;
import net.minecraft.nbt.NbtIo;

/** An authenticated worker save session, used only by the save executor. */
final class StorageWriter implements AutoCloseable {

	private final LodecoreConfig config;
	private final Socket socket = new Socket();
	private DataInputStream input;
	private OutputStream output;
	private long sequence;

	StorageWriter(LodecoreConfig config) { this.config = config; }

	void connect(int node, long epoch) throws IOException {
		socket.setTcpNoDelay(true);
		socket.setSoTimeout(15_000);
		socket.connect(new InetSocketAddress(config.lodestar().getHost(), config.lodestar().getPort()), 5000);
		input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
		output = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
		output.write(StorageProtocol.hello(config.token()));
		output.flush();
		if (!(StorageProtocol.read(input) instanceof Wire.Welcome)) throw new IOException("Lodestar rejected the save connection");
		byte[] binding = ByteBuffer.allocate(12).putInt(node).putLong(epoch).array();
		output.write(StorageProtocol.request(++sequence, StorageProtocol.BIND, "cluster", "save", 0, 0, binding));
		output.flush();
		acknowledge(sequence);
	}

	void upload(long epoch, long revision, int round, Map<SaveCollector.Key, SaveCollector.Snapshot> changes) throws IOException {
		ArrayDeque<Long> acknowledgements = new ArrayDeque<>();
		int buffered = 0;
		for (var entry : changes.entrySet()) {
			SaveCollector.Key key = entry.getKey();
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			bytes.write(ByteBuffer.allocate(20).putLong(epoch).putLong(revision).putInt(round).array());
			if (entry.getValue().data() != null) NbtIo.writeCompressed(entry.getValue().data(), bytes);
			byte[] frame = StorageProtocol.request(++sequence, StorageProtocol.SNAPSHOT,
					key.dimension(), key.kind(), key.x(), key.z(), bytes.toByteArray());
			output.write(frame);
			acknowledgements.add(sequence);
			buffered += frame.length;
			// Pipeline a bounded window instead of paying a network round trip for every chunk.
			if (acknowledgements.size() >= 64 || buffered >= 1 << 20) {
				acknowledgeAll(acknowledgements);
				buffered = 0;
			}
		}
		acknowledgeAll(acknowledgements);
		byte[] complete = ByteBuffer.allocate(22).put((byte) 241).put((byte) 1)
				.putLong(epoch).putLong(revision).putInt(round).array();
		output.write(Wire.direct(0, complete));
		output.flush();
	}

	private void acknowledgeAll(ArrayDeque<Long> ids) throws IOException {
		output.flush();
		while (!ids.isEmpty()) acknowledge(ids.remove());
	}

	private void acknowledge(long id) throws IOException {
		if (!(StorageProtocol.read(input) instanceof Wire.DirectRelay relay) || relay.from() != 0) {
			throw new IOException("Unexpected save response");
		}
		StorageProtocol.Reply reply = StorageProtocol.reply(relay.payload());
		if (reply.id() != id) throw new IOException("Save response is out of order");
		reply.check();
	}

	@Override
	public void close() throws IOException { socket.close(); }
}
