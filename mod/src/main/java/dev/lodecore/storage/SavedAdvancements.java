package dev.lodecore.storage;

import net.minecraft.server.ServerAdvancementManager;

/** Immutable advancement JSON, reused until progress changes. Owned by PlayerAdvancements. */
public interface SavedAdvancements {
	String lodecore$savedAdvancements();
	void lodecore$restoreAdvancements(ServerAdvancementManager manager, String json);
}
