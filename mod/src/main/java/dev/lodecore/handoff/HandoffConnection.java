package dev.lodecore.handoff;

/** Implemented by {@code Connection} through {@code ConnectionMixin}. */
public interface HandoffConnection {
	/**
	 * How many packets have come in on this connection, from the handshake on. Each is handled, or
	 * queued to be handled on the game thread, by the time it is counted.
	 */
	long lodecore$received();

	/**
	 * Cuts the connection loose from the player it served, who has moved to another node: nothing
	 * that comes in is handled any more, and its hanging up goes unnoticed by the game.
	 */
	void lodecore$detach();

	/** Hangs up once everything sent so far has been written. */
	void lodecore$hangUpWhenSent();
}
