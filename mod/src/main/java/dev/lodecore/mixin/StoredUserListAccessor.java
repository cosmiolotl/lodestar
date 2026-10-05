package dev.lodecore.mixin;

import java.util.Map;

import com.google.gson.JsonObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.server.players.StoredUserEntry;
import net.minecraft.server.players.StoredUserList;

@Mixin(StoredUserList.class)
public interface StoredUserListAccessor {
	/** The entries, by the key of their user. */
	@Accessor("map")
	Map<String, ?> lodecore$map();

	@Invoker("createEntry")
	StoredUserEntry<?> lodecore$createEntry(JsonObject object);
}
