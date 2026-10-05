package dev.lodecore.mixin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.stats.ServerStatsCounter;

@Mixin(PlayerList.class)
public interface PlayerListAccessor {
	@Accessor("stats")
	Map<UUID, ServerStatsCounter> lodecore$stats();

	@Accessor("advancements")
	Map<UUID, PlayerAdvancements> lodecore$advancements();

	@Accessor("players")
	List<ServerPlayer> lodecore$players();

	@Accessor("playersByUUID")
	Map<UUID, ServerPlayer> lodecore$playersByUUID();
}
