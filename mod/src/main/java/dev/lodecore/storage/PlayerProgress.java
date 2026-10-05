package dev.lodecore.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import dev.lodecore.mixin.ServerStatsCounterAccessor;
import dev.lodecore.mixin.StatsCounterAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** Statistics and advancement progress travel in the same authoritative save as inventory. */
public final class PlayerProgress {
	private PlayerProgress() { }

	public static void capture(ServerPlayer player, CompoundTag tag) {
		tag.putString("LodecoreAdvancements", ((SavedAdvancements) player.getAdvancements()).lodecore$savedAdvancements());
		tag.putString("LodecoreStats", ((ServerStatsCounterAccessor) player.getStats()).lodecore$toJson().toString());
	}

	public static void prepare(MinecraftServer server, UUID uuid, CompoundTag tag) throws IOException {
		write(server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(uuid + ".json"), tag, "LodecoreAdvancements");
		write(server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(uuid + ".json"), tag, "LodecoreStats");
	}

	private static void write(Path path, CompoundTag tag, String key) throws IOException {
		if (!tag.contains(key)) return; // Old player saves remain readable.
		Files.createDirectories(path.getParent());
		Files.writeString(path, tag.getStringOr(key, "{}"));
	}

	public static void joined(ServerPlayer player, CompoundTag tag) {
		if (tag.contains("LodecoreAdvancements")) player.getAdvancements().reload(player.level().getServer().getAdvancements());
		if (tag.contains("LodecoreStats")) {
			((StatsCounterAccessor) player.getStats()).lodecore$stats().clear();
			player.getStats().parse(player.level().getServer().getFixerUpper(), com.google.gson.JsonParser.parseString(tag.getStringOr("LodecoreStats", "{}")));
			player.getStats().markAllDirty();
		}
	}
}
