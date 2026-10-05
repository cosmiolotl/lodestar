package dev.lodecore.shared;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import dev.lodecore.Node;
import org.jspecify.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;

/**
 * The scoreboard, kept the same on every node: its objectives, scores, display slots, teams and
 * team members, each a kind of {@link SharedData}.
 *
 * <p>A score holder's scores change on whichever node runs the command or counts the criterion:
 * for a player's own criteria, such as deaths or blocks mined, the node the player is on, and for
 * a kill, the node that simulates the victim. Commands, including those of functions, see the
 * scores of every node, since every node has all of them.
 */
public final class ScoreboardSync {
	/** Between the objective and the holder in the key of a score. Names have no control characters. */
	private static final char SEPARATOR = '\u0000';

	private final SharedData data;
	private final Node node;
	private final SharedData.Kind objectives;
	private final SharedData.Kind scores;
	/** Takes away all the scores of a holder. Only ever sent as a removal. */
	private final SharedData.Kind holders;
	private final SharedData.Kind displays;
	private final SharedData.Kind teams;
	private final SharedData.Kind members;

	ScoreboardSync(SharedData data, Node node) {
		this.data = data;
		this.node = node;
		objectives = data.register(new Objectives());
		scores = data.register(new Scores());
		holders = data.register(new Holders());
		displays = data.register(new Displays());
		teams = data.register(new Teams());
		members = data.register(new Members());
	}

	private ServerScoreboard scoreboard() {
		return node.server().getScoreboard();
	}

	private DynamicOps<Tag> ops() {
		return node.server().registryAccess().createSerializationContext(NbtOps.INSTANCE);
	}

	private <T> CompoundTag encode(Codec<T> codec, T value) {
		return (CompoundTag) codec.encodeStart(ops(), value).getOrThrow();
	}

	private <T> T decode(Codec<T> codec, CompoundTag tag) {
		return codec.parse(ops(), tag).getOrThrow();
	}

	private static String scoreKey(String objective, String holder) {
		return objective + SEPARATOR + holder;
	}

	// ---- changes made here ----

	public void onObjectiveChanged(Objective objective) {
		data.changed(objectives, objective.getName());
	}

	/** Its scores and display slots go with it. */
	public void onObjectiveRemoved(Objective objective) {
		data.removed(objectives, objective.getName());
	}

	public void onScoreChanged(ScoreHolder holder, Objective objective) {
		data.changed(scores, scoreKey(objective.getName(), holder.getScoreboardName()));
	}

	public void onHolderRemoved(ScoreHolder holder) {
		data.removed(holders, holder.getScoreboardName());
	}

	public void onDisplayChanged(DisplaySlot slot) {
		data.changed(displays, slot.getSerializedName());
	}

	public void onTeamChanged(PlayerTeam team) {
		data.changed(teams, team.getName());
	}

	/** Its members leave it. */
	public void onTeamRemoved(PlayerTeam team) {
		data.removed(teams, team.getName());
	}

	public void onMemberChanged(String holder) {
		data.changed(members, holder);
	}

	// ---- the kinds ----

	private final class Objectives extends SharedData.Kind {
		Objectives() {
			super("objective");
		}

		@Override
		protected @Nullable CompoundTag get(String key) {
			Objective objective = scoreboard().getObjective(key);
			return objective == null ? null : encode(Objective.Packed.CODEC, objective.pack());
		}

		@Override
		protected void set(String key, @Nullable CompoundTag value) {
			ServerScoreboard scoreboard = scoreboard();
			Objective objective = scoreboard.getObjective(key);

			if (value == null) {
				if (objective != null) {
					scoreboard.removeObjective(objective);
				}

				return;
			}

			Objective.Packed packed = decode(Objective.Packed.CODEC, value);

			// An objective does not change what it counts.
			if (objective != null && objective.getCriteria() != packed.criteria()) {
				scoreboard.removeObjective(objective);
				objective = null;
			}

			if (objective == null) {
				scoreboard.addObjective(key, packed.criteria(), packed.displayName(), packed.renderType(), packed.displayAutoUpdate(), packed.numberFormat().orElse(null));
				return;
			}

			if (!objective.getDisplayName().equals(packed.displayName())) {
				objective.setDisplayName(packed.displayName());
			}

			if (objective.getRenderType() != packed.renderType()) {
				objective.setRenderType(packed.renderType());
			}

			if (objective.displayAutoUpdate() != packed.displayAutoUpdate()) {
				objective.setDisplayAutoUpdate(packed.displayAutoUpdate());
			}

			if (!Objects.equals(objective.numberFormat(), packed.numberFormat().orElse(null))) {
				objective.setNumberFormat(packed.numberFormat().orElse(null));
			}
		}

		@Override
		protected Collection<String> keys() {
			return List.copyOf(scoreboard().getObjectiveNames());
		}
	}

	private final class Scores extends SharedData.Kind {
		private static final Codec<Score.Packed> CODEC = Score.Packed.MAP_CODEC.codec();

		Scores() {
			super("score");
		}

		@Override
		protected @Nullable CompoundTag get(String key) {
			int split = key.indexOf(SEPARATOR);
			Objective objective = scoreboard().getObjective(key.substring(0, split));

			if (objective == null) {
				return null;
			}

			ReadOnlyScoreInfo info = scoreboard().getPlayerScoreInfo(ScoreHolder.forNameOnly(key.substring(split + 1)), objective);
			return info instanceof Score score ? encode(CODEC, score.pack()) : null;
		}

		/** Through a score's access, as a command does, which tells the players who see it. */
		@Override
		protected void set(String key, @Nullable CompoundTag value) {
			ServerScoreboard scoreboard = scoreboard();
			int split = key.indexOf(SEPARATOR);
			Objective objective = scoreboard.getObjective(key.substring(0, split));
			ScoreHolder holder = ScoreHolder.forNameOnly(key.substring(split + 1));

			if (objective == null) {
				return;
			}

			ReadOnlyScoreInfo info = scoreboard.getPlayerScoreInfo(holder, objective);

			if (value == null) {
				if (info != null) {
					scoreboard.resetSinglePlayerScore(holder, objective);
				}

				return;
			}

			Score.Packed packed = decode(CODEC, value);
			ScoreAccess access = scoreboard.getOrCreatePlayerScore(holder, objective, true);
			access.set(packed.value());
			access.display(packed.display().orElse(null));
			NumberFormat format = packed.numberFormat().orElse(null);

			if (info == null || !Objects.equals(info.numberFormat(), format)) {
				access.numberFormatOverride(format);
			}

			if (access.locked() != packed.locked()) {
				if (packed.locked()) {
					access.lock();
				} else {
					access.unlock();
				}
			}
		}

		@Override
		protected Collection<String> keys() {
			List<String> keys = new ArrayList<>();

			for (Objective objective : scoreboard().getObjectives()) {
				for (PlayerScoreEntry entry : scoreboard().listPlayerScores(objective)) {
					keys.add(scoreKey(objective.getName(), entry.owner()));
				}
			}

			return keys;
		}
	}

	private final class Holders extends SharedData.Kind {
		Holders() {
			super("score holder");
		}

		@Override
		protected @Nullable CompoundTag get(String key) {
			return null;
		}

		@Override
		protected void set(String key, @Nullable CompoundTag value) {
			if (value == null) {
				scoreboard().resetAllPlayerScores(ScoreHolder.forNameOnly(key));
			}
		}

		@Override
		protected Collection<String> keys() {
			return List.of();
		}
	}

	private final class Displays extends SharedData.Kind {
		Displays() {
			super("display slot");
		}

		@Override
		protected @Nullable CompoundTag get(String key) {
			DisplaySlot slot = DisplaySlot.CODEC.byName(key);
			Objective objective = slot == null ? null : scoreboard().getDisplayObjective(slot);

			if (objective == null) {
				return null;
			}

			CompoundTag tag = new CompoundTag();
			tag.putString("objective", objective.getName());
			return tag;
		}

		@Override
		protected void set(String key, @Nullable CompoundTag value) {
			DisplaySlot slot = DisplaySlot.CODEC.byName(key);

			if (slot == null) {
				return;
			}

			Objective objective = value == null ? null : scoreboard().getObjective(value.getStringOr("objective", ""));

			if (scoreboard().getDisplayObjective(slot) != objective) {
				scoreboard().setDisplayObjective(slot, objective);
			}
		}

		@Override
		protected Collection<String> keys() {
			List<String> keys = new ArrayList<>();

			for (DisplaySlot slot : DisplaySlot.values()) {
				if (scoreboard().getDisplayObjective(slot) != null) {
					keys.add(slot.getSerializedName());
				}
			}

			return keys;
		}
	}

	/** A team's options. Its members are a kind of their own, so that each node changes only those it changes. */
	private final class Teams extends SharedData.Kind {
		Teams() {
			super("team");
		}

		@Override
		protected @Nullable CompoundTag get(String key) {
			PlayerTeam team = scoreboard().getPlayerTeam(key);

			if (team == null) {
				return null;
			}

			CompoundTag tag = encode(PlayerTeam.Packed.CODEC, team.pack());
			tag.remove("Players");
			return tag;
		}

		@Override
		protected void set(String key, @Nullable CompoundTag value) {
			ServerScoreboard scoreboard = scoreboard();
			PlayerTeam team = scoreboard.getPlayerTeam(key);

			if (value == null) {
				if (team != null) {
					scoreboard.removePlayerTeam(team);
				}

				return;
			}

			PlayerTeam.Packed packed = decode(PlayerTeam.Packed.CODEC, value);

			if (team == null) {
				team = scoreboard.addPlayerTeam(key);
			}

			// Each setter tells every player, so only what differs is set.
			Component displayName = packed.displayName().orElse(Component.literal(key));

			if (!team.getDisplayName().equals(displayName)) {
				team.setDisplayName(displayName);
			}

			if (!team.getColor().equals(packed.color())) {
				team.setColor(packed.color());
			}

			if (team.isAllowFriendlyFire() != packed.allowFriendlyFire()) {
				team.setAllowFriendlyFire(packed.allowFriendlyFire());
			}

			if (team.canSeeFriendlyInvisibles() != packed.seeFriendlyInvisibles()) {
				team.setSeeFriendlyInvisibles(packed.seeFriendlyInvisibles());
			}

			if (!team.getPlayerPrefix().equals(packed.memberNamePrefix())) {
				team.setPlayerPrefix(packed.memberNamePrefix());
			}

			if (!team.getPlayerSuffix().equals(packed.memberNameSuffix())) {
				team.setPlayerSuffix(packed.memberNameSuffix());
			}

			if (team.getNameTagVisibility() != packed.nameTagVisibility()) {
				team.setNameTagVisibility(packed.nameTagVisibility());
			}

			if (team.getDeathMessageVisibility() != packed.deathMessageVisibility()) {
				team.setDeathMessageVisibility(packed.deathMessageVisibility());
			}

			if (team.getCollisionRule() != packed.collisionRule()) {
				team.setCollisionRule(packed.collisionRule());
			}
		}

		@Override
		protected Collection<String> keys() {
			return List.copyOf(scoreboard().getTeamNames());
		}
	}

	/** Which team a score holder is in, if any. */
	private final class Members extends SharedData.Kind {
		Members() {
			super("team member");
		}

		@Override
		protected @Nullable CompoundTag get(String key) {
			PlayerTeam team = scoreboard().getPlayersTeam(key);

			if (team == null) {
				return null;
			}

			CompoundTag tag = new CompoundTag();
			tag.putString("team", team.getName());
			return tag;
		}

		@Override
		protected void set(String key, @Nullable CompoundTag value) {
			ServerScoreboard scoreboard = scoreboard();
			PlayerTeam current = scoreboard.getPlayersTeam(key);

			if (value == null) {
				if (current != null) {
					scoreboard.removePlayerFromTeam(key, current);
				}

				return;
			}

			PlayerTeam team = scoreboard.getPlayerTeam(value.getStringOr("team", ""));

			if (team != null && team != current) {
				scoreboard.addPlayerToTeam(key, team);
			}
		}

		@Override
		protected Collection<String> keys() {
			List<String> keys = new ArrayList<>();

			for (PlayerTeam team : scoreboard().getPlayerTeams()) {
				keys.addAll(team.getPlayers());
			}

			return keys;
		}
	}
}
