package dev.lodecore.mixin;

import java.util.List;

import dev.lodecore.replication.Hooks;
import dev.lodecore.shared.GlobalChat;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.Style;
import net.minecraft.server.commands.TeamMsgCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;

/** A team's messages reach its players on every node; see {@link GlobalChat}. */
@Mixin(TeamMsgCommand.class)
abstract class TeamMsgCommandMixin {
	@Shadow
	@Final
	private static Style SUGGEST_STYLE;

	@Inject(method = "sendMessage", at = @At("HEAD"))
	private static void lodecore$announceToTeam(
			CommandSourceStack source, Entity entity, PlayerTeam team, List<ServerPlayer> receivers, PlayerChatMessage message, CallbackInfo ci) {
		GlobalChat chat = Hooks.chat();

		if (chat != null) {
			// As the team's players here are sent it.
			ChatType.Bound incoming = ChatType.bind(ChatType.TEAM_MSG_COMMAND_INCOMING, source)
					.withTargetName(team.getFormattedDisplayName().withStyle(SUGGEST_STYLE));
			chat.onChat(message, source.getPlayer(), incoming, team);
		}
	}
}
