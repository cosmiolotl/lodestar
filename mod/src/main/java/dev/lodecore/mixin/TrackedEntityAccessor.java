package dev.lodecore.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.network.ServerPlayerConnection;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntityAccessor {
	@Accessor("entity")
	net.minecraft.world.entity.Entity lodecore$entity();

	@Invoker("updatePlayers")
	void lodecore$updatePlayers(java.util.List<net.minecraft.server.level.ServerPlayer> players);

	@Accessor("seenBy")
	Set<ServerPlayerConnection> lodecore$seenBy();

	@Accessor("serverEntity")
	ServerEntity lodecore$serverEntity();
	@Invoker("getEffectiveRange")
	int lodecore$effectiveRange();
	@Invoker("updatePlayer")
	void lodecore$updatePlayer(net.minecraft.server.level.ServerPlayer player);
	@Accessor("lastSectionPos")
	net.minecraft.core.SectionPos lodecore$lastSection();
}
