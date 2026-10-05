package dev.lodecore.mixin;

import com.google.gson.JsonObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.server.players.StoredUserEntry;

@Mixin(StoredUserEntry.class)
public interface StoredUserEntryInvoker {
	@Invoker("serialize")
	void lodecore$serialize(JsonObject object);
}
