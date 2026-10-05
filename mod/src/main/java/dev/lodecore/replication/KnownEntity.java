package dev.lodecore.replication;

/**
 * Implemented by {@code Entity} through {@code EntityMixin}. Marks the entities this node has a
 * right to: those it made or was sent, and those it loaded where it is, or may become, the owner.
 * Any other entity that turns up in a region owned elsewhere came from this node's save, and is out
 * of date.
 */
public interface KnownEntity {
	boolean lodecore$known();

	void lodecore$markKnown();
}
