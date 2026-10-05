package dev.lodecore.net;



/** Commits buffered packet tasks before a connection changes ownership. */
public interface BatchedConnection {
	void lodecore$drainPackets();
}
