package dev.lodecore.storage;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import dev.lodecore.net.Wire;

/** Storage requests are Direct messages to Lodestar itself (node zero). */
final class StorageProtocol {

	static final int KIND = 240;
	static final int READ = 0;
	static final int WRITE = 1;
	static final int JOURNAL = 2;
	static final int EPOCH = 4;
	static final int BIND = 5;
	static final int SNAPSHOT = 6;

	private StorageProtocol() { }

	static byte[] request(long id, int operation, String dimension, String kind, int x, int z, byte[] data) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeByte(KIND);
		out.writeLong(id);
		out.writeByte(operation);
		string(out, dimension);
		string(out, kind);
		out.writeInt(x);
		out.writeInt(z);
		out.write(data);
		if (bytes.size() > Wire.MAX_FRAME - 32) throw new IOException("World record exceeds the protocol frame limit");
		return Wire.direct(0, bytes.toByteArray());
	}

	static byte[] hello(String token) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeByte(1);
		out.writeInt(Wire.PROTOCOL_VERSION);
		string(out, token);
		string(out, "world-reader");
		out.writeByte(1); // Authenticated proxy: may read, never write world records.
		return ByteBuffer.allocate(bytes.size() + 4).putInt(bytes.size()).put(bytes.toByteArray()).array();
	}

	private static void string(DataOutputStream out, String value) throws IOException {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		out.writeShort(bytes.length);
		out.write(bytes);
	}

	static Wire.Inbound read(DataInputStream in) throws IOException {
		int length = in.readInt();
		if (length < 1 || length > Wire.MAX_FRAME) throw new IOException("Invalid storage frame length");
		byte[] frame = new byte[length];
		in.readFully(frame);
		try {
			return Wire.decode(ByteBuffer.wrap(frame));
		} catch (Wire.MalformedFrameException e) {
			throw new IOException(e);
		}
	}

	static Reply reply(ByteBuffer payload) throws IOException {
		if (payload.remaining() < 10 || Byte.toUnsignedInt(payload.get()) != KIND) throw new IOException("Invalid storage reply");
		long id = payload.getLong();
		int status = Byte.toUnsignedInt(payload.get());
		byte[] data = new byte[payload.remaining()];
		payload.get(data);
		return new Reply(id, status, data);
	}

	record Reply(long id, int status, byte[] data) {
		void check() throws IOException {
			if (status > 1) throw new IOException("Lodestar storage: " + new String(data, StandardCharsets.UTF_8));
		}
	}
}
