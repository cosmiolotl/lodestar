package dev.lodecore.replication;

import java.util.List;

import net.minecraft.network.syncher.SynchedEntityData;

/**
 * Implemented by {@code SynchedEntityData} through {@code SynchedEntityDataMixin}. The game keeps
 * its own note of which values changed for the clients watching an entity, and clears it when it
 * sends them; replication keeps a separate one.
 */
public interface ReplicatedData {
	/** The values that changed since this was last called, as they are now. */
	List<SynchedEntityData.DataValue<?>> lodecore$takeChanged();

	/**
	 * Sets a value the way the game does, so that the clients watching the entity are told.
	 * Values that do not fit the entity are ignored.
	 */
	void lodecore$assign(SynchedEntityData.DataValue<?> value);
}
