package dev.lodecore.storage;



/** Dirty chunk columns include hidden sections and pending entity loads. */
public interface DirtyEntityStorage {
	void lodecore$dirty(long chunk);
	void lodecore$capture();
}
