package dev.lodecore.replication;

/** Implemented by the waypoint manager to publish final positions once per simulation tick. */
public interface WaypointUpdates {
	void lodecore$flushWaypoints();
}
