package dev.lodecore.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This node's connection to lodestar.
 *
 * <p>The connection is kept up in the background and retried for as long as the link is open.
 * Frames sent while it is down are dropped: everything a node tells lodestar is state that the
 * node announces again from scratch once it is welcomed back.
 */
public final class StarLink {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/link");
	private static final int CONNECT_TIMEOUT_MILLIS = 5000;
	private static final long RETRY_DELAY_MILLIS = 1000;
	private static final int OUTBOUND_QUEUE = 64 * 1024;

	/** Called on the link's own thread, in the order things happened. */
	public interface Listener {
		/** The link is up. Everything sent before this call was lost. */
		void onConnected(int nodeId);

		void onMessage(Wire.Inbound message);

		void onDisconnected();
	}

	private final InetSocketAddress address;
	private final byte[] hello;
	private final Listener listener;
	private final Thread thread;

	private volatile boolean closed;
	/** The outbound queue of the live connection, or null while disconnected. */
	private volatile BlockingQueue<byte[]> outbound;
	private volatile Socket socket;

	public StarLink(InetSocketAddress address, byte[] hello, Listener listener) {
		this.address = address;
		this.hello = hello;
		this.listener = listener;
		this.thread = new Thread(this::run, "lodecore-lodestar");
		this.thread.setDaemon(true);
	}

	public void start() {
		thread.start();
	}

	public void close() {
		closed = true;
		thread.interrupt();
		closeQuietly(socket);
	}

	/** Queues a frame. Safe to call from any thread. */
	public void send(byte[] frame) {
		BlockingQueue<byte[]> queue = outbound;

		if (queue != null && !queue.offer(frame)) {
			// lodestar has stopped reading. Start over rather than buffer without bound.
			LOGGER.warn("lodestar is not keeping up, reconnecting");
			closeQuietly(socket);
		}
	}

	private void run() {
		while (!closed) {
			try {
				session();
			} catch (IOException | Wire.MalformedFrameException e) {
				if (!closed) {
					LOGGER.warn("Lost lodestar at {}: {}", address, e.toString());
				}
			}

			try {
				Thread.sleep(RETRY_DELAY_MILLIS);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	private void session() throws IOException, Wire.MalformedFrameException {
		try (Socket socket = new Socket()) {
			this.socket = socket;
			socket.setTcpNoDelay(true);
			socket.connect(address, CONNECT_TIMEOUT_MILLIS);
			DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
			OutputStream out = socket.getOutputStream();

			out.write(hello);
			out.flush();

			switch (read(in)) {
				case Wire.Welcome welcome -> {
					LOGGER.info("Connected to lodestar at {} as node #{}", address, welcome.nodeId());
					BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(OUTBOUND_QUEUE);
					Thread writer = new Thread(() -> write(socket, out, queue), "lodecore-lodestar-writer");
					writer.setDaemon(true);
					writer.start();

					// Open the queue before announcing the link, so the listener can send right away.
					outbound = queue;
					listener.onConnected(welcome.nodeId());

					try {
						while (true) {
							listener.onMessage(read(in));
						}
					} finally {
						outbound = null;
						writer.interrupt();
						listener.onDisconnected();
					}
				}
				case Wire.Rejected rejected -> throw new IOException("lodestar rejected this node: " + rejected.reason());
				default -> throw new IOException("lodestar did not answer Hello with Welcome");
			}
		}
	}

	private static Wire.Inbound read(DataInputStream in) throws IOException, Wire.MalformedFrameException {
		int length = in.readInt();

		if (length <= 0 || length > Wire.MAX_FRAME) {
			throw new Wire.MalformedFrameException("invalid frame length " + length);
		}

		byte[] body = new byte[length];

		try {
			in.readFully(body);
		} catch (EOFException e) {
			throw new IOException("connection closed mid-frame");
		}

		return Wire.decode(ByteBuffer.wrap(body));
	}

	private static void write(Socket socket, OutputStream raw, BlockingQueue<byte[]> queue) {
		try (OutputStream out = new BufferedOutputStream(raw, 64 * 1024)) {
			while (true) {
				out.write(queue.take());

				// Coalesce whatever is already waiting into one flush.
				for (byte[] frame = queue.poll(); frame != null; frame = queue.poll()) {
					out.write(frame);
				}

				out.flush();
			}
		} catch (InterruptedException e) {
			// The session is over.
		} catch (IOException e) {
			// Wake the reader, which reports the failure and reconnects.
			closeQuietly(socket);
		}
	}

	private static void closeQuietly(Socket socket) {
		if (socket != null) {
			try {
				socket.close();
			} catch (IOException e) {
				// Nothing left to do with it.
			}
		}
	}
}
