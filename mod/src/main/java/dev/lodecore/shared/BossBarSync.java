package dev.lodecore.shared;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.DynamicOps;
import dev.lodecore.Node;
import dev.lodecore.mixin.CustomBossEventAccessor;
import org.jspecify.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.bossevents.CustomBossEvent;
import net.minecraft.server.bossevents.CustomBossEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

/**
 * The boss bars made with {@code /bossbar}, kept the same on every node: each bar's options are a
 * kind of {@link SharedData}, and which players are on it another.
 *
 * <p>A node's commands only see the players on that node, so {@code /bossbar set ... players} on
 * one node says nothing of the players on the others. It changes which of this node's players,
 * and of the players not on any node, are on the bar, and leaves the players on other nodes as
 * they were. That way a datapack's functions, which run on every node, each set the bar's players
 * among their own.
 */
public final class BossBarSync {
	/** Between the bar and the player in the key of a player on a bar. Ids have no control characters. */
	private static final char SEPARATOR = '\u0000';

	private final SharedData data;
	private final Node node;
	private final SharedData.Kind bars;
	private final SharedData.Kind players;
	/** Bars whose players may have changed here since the end of the last tick. */
	private final Set<Identifier> touched = new HashSet<>();
	/** The players of each bar as the rest of the cluster knows them. */
	private final Map<Identifier, Set<UUID>> known = new HashMap<>();
	private final RandomSource random = RandomSource.create();

	BossBarSync(SharedData data, Node node) {
		this.data = data;
		this.node = node;
		bars = data.register(new Bars());
		players = data.register(new Players());
	}

	private CustomBossEvents events() {
		return node.server().getCustomBossEvents();
	}

	private static Set<UUID> playersOf(CustomBossEvent bar) {
		return ((CustomBossEventAccessor) bar).lodecore$players();
	}

	// ---- changes made here ----

	/** Something about a bar changed here: its options, or its players. */
	public void onChanged(CustomBossEvent bar) {
		if (data.recording()) {
			data.changed(bars, bar.customId().toString());
			touched.add(bar.customId());
		}
	}

	/** Its players leave it. */
	public void onRemoved(CustomBossEvent bar) {
		if (data.recording()) {
			data.removed(bars, bar.customId().toString());
			touched.remove(bar.customId());
		}

		known.remove(bar.customId());
	}

	/**
	 * Before what changed is sent: notes which players joined or left the bars touched this tick.
	 * A player another node has, this node's commands could not see, so they stay on the bar.
	 */
	void collectMembership() {
		for (Identifier id : touched) {
			CustomBossEvent bar = events().get(id);

			if (bar == null) {
				continue;
			}

			Set<UUID> now = playersOf(bar);
			Set<UUID> before = known.getOrDefault(id, Set.of());

			for (UUID uuid : before) {
				if (now.contains(uuid)) {
					continue;
				}

				int home = node.homeOf(uuid);

				if (home != Node.NO_NODE && home != node.nodeId()) {
					now.add(uuid);
				} else {
					data.changed(players, key(id, uuid));
				}
			}

			for (UUID uuid : now) {
				if (!before.contains(uuid)) {
					data.changed(players, key(id, uuid));
				}
			}

			known.put(id, new HashSet<>(now));
		}

		touched.clear();
	}

	void onDisconnected() {
		touched.clear();
		known.clear();
	}

	private static String key(Identifier bar, UUID player) {
		return bar.toString() + SEPARATOR + player;
	}

	// ---- the kinds ----

	private final class Bars extends SharedData.Kind {
		Bars() {
			super("boss bar");
		}

		private DynamicOps<Tag> ops() {
			return node.server().registryAccess().createSerializationContext(NbtOps.INSTANCE);
		}

		@Override
		protected @Nullable CompoundTag get(String key) {
			CustomBossEvent bar = events().get(Identifier.parse(key));

			if (bar == null) {
				return null;
			}

			CompoundTag tag = (CompoundTag) CustomBossEvent.Packed.CODEC.encodeStart(ops(), bar.pack()).getOrThrow();
			tag.remove("Players");
			return tag;
		}

		/** Through the setters {@code /bossbar} uses, which tell the bar's players. */
		@Override
		protected void set(String key, @Nullable CompoundTag value) {
			Identifier id = Identifier.parse(key);
			CustomBossEvent bar = events().get(id);

			if (value == null) {
				if (bar != null) {
					bar.removeAllPlayers();
					events().remove(bar);
				}

				known.remove(id);
				return;
			}

			CustomBossEvent.Packed packed = CustomBossEvent.Packed.CODEC.parse(ops(), value).getOrThrow();

			if (bar == null) {
				bar = events().create(random, id, packed.name());
			}

			if (!bar.getName().equals(packed.name())) {
				bar.setName(packed.name());
			}

			if (bar.isVisible() != packed.visible()) {
				bar.setVisible(packed.visible());
			}

			if (bar.max() != packed.max()) {
				bar.setMax(packed.max());
			}

			if (bar.value() != packed.value()) {
				bar.setValue(packed.value());
			}

			if (bar.getColor() != packed.color()) {
				bar.setColor(packed.color());
			}

			if (bar.getOverlay() != packed.overlay()) {
				bar.setOverlay(packed.overlay());
			}

			if (bar.shouldDarkenScreen() != packed.darkenScreen()) {
				bar.setDarkenScreen(packed.darkenScreen());
			}

			if (bar.shouldPlayBossMusic() != packed.playBossMusic()) {
				bar.setPlayBossMusic(packed.playBossMusic());
			}

			if (bar.shouldCreateWorldFog() != packed.createWorldFog()) {
				bar.setCreateWorldFog(packed.createWorldFog());
			}
		}

		@Override
		protected Collection<String> keys() {
			return events().getIds().stream().map(Identifier::toString).toList();
		}
	}

	/** Whether a player is on a bar. A player on another node is only noted, for when they come here. */
	private final class Players extends SharedData.Kind {
		Players() {
			super("boss bar player");
		}

		@Override
		protected @Nullable CompoundTag get(String key) {
			int split = key.indexOf(SEPARATOR);
			CustomBossEvent bar = events().get(Identifier.parse(key.substring(0, split)));
			return bar != null && playersOf(bar).contains(UUID.fromString(key.substring(split + 1))) ? new CompoundTag() : null;
		}

		@Override
		protected void set(String key, @Nullable CompoundTag value) {
			int split = key.indexOf(SEPARATOR);
			Identifier id = Identifier.parse(key.substring(0, split));
			UUID uuid = UUID.fromString(key.substring(split + 1));
			CustomBossEvent bar = events().get(id);

			if (bar == null) {
				return;
			}

			ServerPlayer player = node.server().getPlayerList().getPlayer(uuid);
			Set<UUID> on = playersOf(bar);

			if (value != null) {
				on.add(uuid);

				if (player != null) {
					bar.addPlayer(player);
				}
			} else if (player != null) {
				bar.removePlayer(player);
			} else {
				on.remove(uuid);
			}

			known.put(id, new HashSet<>(on));
		}

		@Override
		protected Collection<String> keys() {
			List<String> keys = new ArrayList<>();

			for (CustomBossEvent bar : events().getEvents()) {
				for (UUID uuid : playersOf(bar)) {
					keys.add(key(bar.customId(), uuid));
				}
			}

			return keys;
		}
	}
}
