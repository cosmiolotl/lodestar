package dev.lodecore.storage;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import dev.lodecore.LodecoreConfig;
import dev.lodecore.net.Wire;

/** Available before the server loads its first chunk; independent of the tick barrier. */
final class StorageReader implements AutoCloseable {
	private final LodecoreConfig config;
	private Socket socket;
	private DataInputStream input;
	private long sequence;

	StorageReader(LodecoreConfig config) { this.config = config; }

	synchronized StorageProtocol.Reply request(int operation, String dimension, String kind, int x, int z) throws IOException {
		try {
			if (socket == null) connect();
			long id = ++sequence;
			socket.getOutputStream().write(StorageProtocol.request(id, operation, dimension, kind, x, z, new byte[0]));
			Wire.Inbound message = StorageProtocol.read(input);
			if (!(message instanceof Wire.DirectRelay relay) || relay.from() != 0) throw new IOException("Unexpected storage response");
			StorageProtocol.Reply reply = StorageProtocol.reply(relay.payload());
			if (reply.id() != id) throw new IOException("Storage response is out of order");
			reply.check();
			return reply;
		} catch (IOException e) {
			close();
			throw e;
		}
	}

	private void connect() throws IOException {
		socket = new Socket();
		socket.setTcpNoDelay(true);
		socket.setSoTimeout(15_000);
		socket.connect(new InetSocketAddress(config.lodestar().getHost(), config.lodestar().getPort()), 5000);
		input = new DataInputStream(socket.getInputStream());
		socket.getOutputStream().write(StorageProtocol.hello(config.token()));
		if (!(StorageProtocol.read(input) instanceof Wire.Welcome)) throw new IOException("Lodestar rejected world storage authentication");
	}

	@Override
	public synchronized void close() throws IOException {
		if (socket != null) {
			try { socket.close(); } finally { socket = null; }
		}
	}
}
