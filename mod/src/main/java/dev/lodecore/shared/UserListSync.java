package dev.lodecore.shared;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lodecore.Node;
import dev.lodecore.mixin.StoredUserEntryInvoker;
import dev.lodecore.mixin.StoredUserListAccessor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.StoredUserEntry;
import net.minecraft.server.players.StoredUserList;

/**
 * Who may join and who is an operator, kept the same on every node: the operator list, the
 * whitelist, the ban lists, and whether the whitelist is on and enforced, each a kind of
 * {@link SharedData}. Taking on another node's change does what the command would have done
 * here: a player banned or taken off an enforced whitelist is disconnected, and an operator is
 * told their new permissions.
 */
public final class UserListSync {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/shared");
	public static final String WHITELIST_ON = "white-list";
	public static final String WHITELIST_ENFORCED = "enforce-whitelist";

	private final SharedData data;
	private final Node node;
	private final SharedData.Kind ops;
	private final SharedData.Kind whitelist;
	private final SharedData.Kind bans;
	private final SharedData.Kind ipBans;
	private final SharedData.Kind flags;

	UserListSync(SharedData data, Node node) {
		this.data = data;
		this.node = node;
		ops = data.register(new ListKind("operator", PlayerList::getOps));
		whitelist = data.register(new ListKind("whitelist entry", PlayerList::getWhiteList));
		bans = data.register(new ListKind("ban", PlayerList::getBans));
		ipBans = data.register(new ListKind("IP ban", PlayerList::getIpBans));
		flags = data.register(new Flags());
	}

	/** An entry of one of the lists was added or taken away here, by its key in the list. */
	public void onChanged(StoredUserList<?, ?> list, String key) {
		PlayerList players = node.server().getPlayerList();
		SharedData.Kind kind = list == players.getOps() ? ops
				: list == players.getWhiteList() ? whitelist
				: list == players.getBans() ? bans
				: list == players.getIpBans() ? ipBans
				: null;

		if (kind != null) {
			data.changed(kind, key);
		}
	}

	/** The whitelist was switched on or off, or its enforcement: {@link #WHITELIST_ON} or {@link #WHITELIST_ENFORCED}. */
	public void onFlagChanged(String flag) {
		data.changed(flags, flag);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, StoredUserEntry<?>> entries(StoredUserList<?, ?> list) {
		return (Map<String, StoredUserEntry<?>>) ((StoredUserListAccessor) list).lodecore$map();
	}

	private final class ListKind extends SharedData.Kind {
		private final Function<PlayerList, StoredUserList<?, ?>> list;

		ListKind(String name, Function<PlayerList, StoredUserList<?, ?>> list) {
			super(name);
			this.list = list;
		}

		private StoredUserList<?, ?> list() {
			return list.apply(node.server().getPlayerList());
		}

		@Override
		protected @Nullable CompoundTag get(String key) {
			StoredUserEntry<?> entry = entries(list()).get(key);

			if (entry == null) {
				return null;
			}

			JsonObject json = new JsonObject();
			((StoredUserEntryInvoker) entry).lodecore$serialize(json);
			// As the list's own file leaves them out: an entry cannot read them back.
			json.entrySet().removeIf(member -> member.getValue().isJsonNull());
			CompoundTag tag = new CompoundTag();
			tag.putString("entry", json.toString());
			return tag;
		}

		@Override
		@SuppressWarnings({"unchecked", "rawtypes"})
		protected void set(String key, @Nullable CompoundTag value) {
			StoredUserList list = list();
			Map<String, StoredUserEntry<?>> entries = entries(list);
			StoredUserEntry<?> entry;

			if (value == null) {
				entry = entries.remove(key);

				if (entry == null) {
					return;
				}

				try {
					list.save();
				} catch (IOException e) {
					LOGGER.warn("Could not save {} after taking away {}: {}", list.getFile(), key, e.toString());
				}
			} else {
				JsonObject json = JsonParser.parseString(value.getStringOr("entry", "{}")).getAsJsonObject();
				entry = ((StoredUserListAccessor) list).lodecore$createEntry(json);

				if (entry.getUser() == null || !list.add(entry)) {
					return;
				}
			}

			onTaken(this, entry, value != null);
		}

		@Override
		protected Collection<String> keys() {
			return List.copyOf(entries(list()).keySet());
		}
	}

	/** Does here what the command that changed a list did on the node it ran on. */
	private void onTaken(SharedData.Kind kind, StoredUserEntry<?> entry, boolean added) {
		MinecraftServer server = node.server();
		PlayerList players = server.getPlayerList();
		ServerPlayer player = entry.getUser() instanceof NameAndId user ? players.getPlayer(user.id()) : null;

		if (kind == ops) {
			if (player != null) {
				players.sendPlayerPermissionLevel(player);
			}
		} else if (kind == whitelist) {
			if (!added) {
				server.kickUnlistedPlayers();
			}
		} else if (kind == bans) {
			if (added && player != null) {
				player.connection.disconnect(Component.translatable("multiplayer.disconnect.banned"));
			}
		} else if (kind == ipBans && added && entry.getUser() instanceof String ip) {
			for (ServerPlayer banned : List.copyOf(players.getPlayersWithAddress(ip))) {
				banned.connection.disconnect(Component.translatable("multiplayer.disconnect.ip_banned"));
			}
		}
	}

	private final class Flags extends SharedData.Kind {
		Flags() {
			super("whitelist setting");
		}

		@Override
		protected @Nullable CompoundTag get(String key) {
			MinecraftServer server = node.server();
			CompoundTag tag = new CompoundTag();

			switch (key) {
				case WHITELIST_ON -> tag.putBoolean("on", server.isUsingWhitelist());
				case WHITELIST_ENFORCED -> tag.putBoolean("on", server.isEnforceWhitelist());
				default -> {
					return null;
				}
			}

			return tag;
		}

		@Override
		protected void set(String key, @Nullable CompoundTag value) {
			MinecraftServer server = node.server();

			if (value == null) {
				return;
			}

			boolean on = value.getBooleanOr("on", false);

			switch (key) {
				case WHITELIST_ON -> {
					if (server.isUsingWhitelist() != on) {
						server.setUsingWhitelist(on);
					}
				}
				case WHITELIST_ENFORCED -> {
					if (server.isEnforceWhitelist() != on) {
						server.setEnforceWhitelist(on);
					}
				}
				default -> {
					return;
				}
			}

			server.kickUnlistedPlayers();
		}

		@Override
		protected Collection<String> keys() {
			return List.of(WHITELIST_ON, WHITELIST_ENFORCED);
		}
	}
}
