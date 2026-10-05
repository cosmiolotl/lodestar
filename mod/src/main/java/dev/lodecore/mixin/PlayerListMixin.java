package dev.lodecore.mixin;

import java.util.function.Function;
import java.util.function.Predicate;

import dev.lodecore.replication.Hooks;
import dev.lodecore.shared.GlobalChat;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;

@Mixin(PlayerList.class)
abstract class PlayerListMixin {
	/** Checkpoints already capture all players; bulk vanilla saves would compress them again on the tick thread. */
	@Inject(method = "saveAll", at = @At("HEAD"), cancellable = true)
	private void lodecore$checkpointSavesPlayers(CallbackInfo ci) {
		if (dev.lodecore.storage.WorldStorage.enabled()) ci.cancel();
	}

	/** Vanilla reload reads advancement files back; refresh that import only when explicitly reloading. */
	@Inject(method = "reloadResources", at = @At("HEAD"))
	private void lodecore$preserveProgressOnReload(CallbackInfo ci) {
		if (!dev.lodecore.storage.WorldStorage.enabled()) return;
		for (ServerPlayer player : ((PlayerList) (Object) this).getPlayers()) player.getAdvancements().save();
	}

	/** A player that leaves has been saved; their data goes back to lodestar with that save. */
	@Inject(method = "remove", at = @At("TAIL"))
	private void lodecore$giveBackPlayerData(ServerPlayer player, CallbackInfo ci) {
		Hooks.onPlayerRemoved(player);
	}

	// What goes to every player here goes to every player in the cluster; see GlobalChat.

	@Inject(
			method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Ljava/util/function/Predicate;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
			at = @At("HEAD"))
	private void lodecore$announceChat(PlayerChatMessage message, Predicate<ServerPlayer> isFiltered, @Nullable ServerPlayer sender, ChatType.Bound chatType, CallbackInfo ci) {
		GlobalChat chat = Hooks.chat();

		if (chat != null) {
			chat.onChat(message, sender, chatType, null);
		}
	}

	@Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Ljava/util/function/Function;Z)V", at = @At("HEAD"))
	private void lodecore$announceSystemMessage(Component message, Function<ServerPlayer, Component> playerMessages, boolean overlay, CallbackInfo ci) {
		GlobalChat chat = Hooks.chat();

		if (chat != null) {
			chat.onSystem(message, overlay);
		}
	}

	/** A player's death, told to their team. Without a team, nobody is told. */
	@Inject(method = "broadcastSystemToTeam", at = @At("HEAD"))
	private void lodecore$announceToTeam(Player player, Component message, CallbackInfo ci) {
		GlobalChat chat = Hooks.chat();

		if (chat != null && player instanceof ServerPlayer serverPlayer && player.getTeam() != null) {
			chat.onSystemToTeam(serverPlayer, message);
		}
	}

	/** A player's death, told to everyone not on their team. Without a team, that is everyone, which the game announces as such. */
	@Inject(method = "broadcastSystemToAllExceptTeam", at = @At("HEAD"))
	private void lodecore$announceToOthers(Player player, Component message, CallbackInfo ci) {
		GlobalChat chat = Hooks.chat();

		if (chat != null && player instanceof ServerPlayer serverPlayer && player.getTeam() != null) {
			chat.onSystemToOthers(serverPlayer, message);
		}
	}
}
