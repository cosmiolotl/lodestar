package dev.lodecore.shared;

import java.nio.ByteBuffer;

import dev.lodecore.Node;
import dev.lodecore.net.Wire;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.FilterMask;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;

/**
 * Chat, and what the game announces to every player, heard on every node.
 *
 * <p>A node's players chat, and its game announces joins, departures, deaths and advancements,
 * to the players of that node, as a single server does to all of its players. Each of these is
 * also announced to the rest of the cluster through lodestar, and every other node passes it on
 * to its own players, as it would one of its own: a team's messages to the team's players there,
 * a death message to the players the dead player's team lets see it.
 *
 * <p>A player's chat reaches the players of other nodes unsigned, as the game sends chat that is
 * not a player's, such as {@code /say} from the console: their clients may not know the sender,
 * which a signed message needs. It looks the same, and goes only to players who show chat.
 *
 * <p>Everything here runs on the game thread.
 */
public final class GlobalChat {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/chat");

	// Payloads, announced to the whole cluster.
	/** A chat message, to every player or to a team's. */
	private static final int CHAT = 30;
	/** Something the game announces. */
	private static final int SYSTEM = 31;

	// Who a system message is for.
	/** Every player. */
	private static final int EVERYONE = 0;
	/** The players of a player's team, but that player. */
	private static final int TEAM = 1;
	/** The players not on a player's team. */
	private static final int NOT_TEAM = 2;

	private final Node node;

	public GlobalChat(Node node) {
		this.node = node;
	}

	public static boolean handles(int kind) {
		return kind == CHAT || kind == SYSTEM;
	}

	// ---- said here ----

	/**
	 * A chat message went out to this node's players: to all of them, or, if {@code team} is
	 * given, to that team's.
	 */
	public void onChat(PlayerChatMessage message, @Nullable ServerPlayer sender, ChatType.Bound chatType, @Nullable PlayerTeam team) {
		if (!node.isConnected()) {
			return;
		}

		RegistryFriendlyByteBuf out = start(CHAT);
		out.writeUtf(team == null ? "" : team.getName());
		ChatType.Bound.STREAM_CODEC.encode(out, chatType);
		ComponentSerialization.TRUSTED_STREAM_CODEC.encode(out, message.decoratedContent());
		out.writeUtf(message.signedContent());
		FilterMask.STREAM_CODEC.encode(out, message.filterMask());
		// Whether the sender has their messages filtered for everyone.
		out.writeBoolean(sender != null && sender.isTextFilteringEnabled());
		node.send(Wire.announce(ByteBufUtil.getBytes(out)));
	}

	/** The game announced something to every player here. */
	public void onSystem(Component message, boolean overlay) {
		system(message, overlay, EVERYONE, null);
	}

	/** The game told the players of a player's team something, such as the player's death. */
	public void onSystemToTeam(ServerPlayer player, Component message) {
		system(message, false, TEAM, player);
	}

	/** The game told the players not on a player's team something. */
	public void onSystemToOthers(ServerPlayer player, Component message) {
		system(message, false, NOT_TEAM, player);
	}

	private void system(Component message, boolean overlay, int audience, @Nullable ServerPlayer player) {
		if (!node.isConnected()) {
			return;
		}

		RegistryFriendlyByteBuf out = start(SYSTEM);
		out.writeByte(audience);

		if (player != null) {
			PlayerTeam team = player.getTeam();
			out.writeUtf(player.getScoreboardName());
			out.writeUtf(team == null ? "" : team.getName());
		}

		ComponentSerialization.TRUSTED_STREAM_CODEC.encode(out, message);
		out.writeBoolean(overlay);
		node.send(Wire.announce(ByteBufUtil.getBytes(out)));
	}

	private RegistryFriendlyByteBuf start(int kind) {
		RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), node.server().registryAccess());
		out.writeByte(kind);
		return out;
	}

	// ---- said elsewhere ----

	/** Another node announced something: passes it on to this node's players. */
	public void onAnnounce(int from, ByteBuffer payload) {
		try {
			RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload), node.server().registryAccess());
			int kind = in.readByte();

			switch (kind) {
				case CHAT -> chat(from, in);
				case SYSTEM -> system(in);
				default -> LOGGER.warn("Node #{} announced an unknown payload {}", from, kind);
			}
		} catch (IndexOutOfBoundsException | DecoderException | IllegalArgumentException e) {
			LOGGER.warn("Node #{} announced a malformed payload: {}", from, e.toString());
		}
	}

	private void chat(int from, RegistryFriendlyByteBuf in) {
		String teamName = in.readUtf();
		ChatType.Bound chatType = ChatType.Bound.STREAM_CODEC.decode(in);
		Component content = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(in);
		String signedContent = in.readUtf();
		FilterMask mask = FilterMask.STREAM_CODEC.decode(in);
		boolean filteredForAll = in.readBoolean();
		PlayerTeam team = teamName.isEmpty() ? null : node.server().getScoreboard().getPlayerTeam(teamName);

		if (!teamName.isEmpty() && team == null) {
			return;
		}

		if (team == null) {
			node.server().logChatMessage(content, chatType, "Node #" + from);
		}

		for (ServerPlayer player : node.server().getPlayerList().getPlayers()) {
			if (team != null && player.getTeam() != team) {
				continue;
			}

			Component shown = content;

			// As the game shows a filtered message: what is left of its text, if anything.
			if ((filteredForAll || player.isTextFilteringEnabled()) && !mask.isEmpty()) {
				String left = mask.apply(signedContent);

				if (left == null) {
					continue;
				}

				shown = Component.literal(left);
			}

			player.sendChatMessage(new OutgoingChatMessage.Disguised(shown), false, chatType);
		}
	}

	private void system(RegistryFriendlyByteBuf in) {
		int audience = in.readByte();
		String playerName = audience == EVERYONE ? "" : in.readUtf();
		String teamName = audience == EVERYONE ? "" : in.readUtf();
		Component message = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(in);
		boolean overlay = in.readBoolean();
		PlayerTeam team = teamName.isEmpty() ? null : node.server().getScoreboard().getPlayerTeam(teamName);

		if (audience == EVERYONE || audience == NOT_TEAM && team == null) {
			node.server().sendSystemMessage(message);
		}

		for (ServerPlayer player : node.server().getPlayerList().getPlayers()) {
			boolean onTeam = team != null && player.getTeam() == team;
			boolean hears = switch (audience) {
				case TEAM -> onTeam && !player.getScoreboardName().equals(playerName);
				case NOT_TEAM -> !onTeam;
				default -> true;
			};

			if (hears) {
				player.sendSystemMessage(message, overlay);
			}
		}
	}
}
