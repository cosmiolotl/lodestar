package dev.lodecore.handoff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;

/**
 * Every boss bar there is. A client shows a boss bar by the id of the bar on the node that added
 * it, so a player leaving a node is taken off its bars there, and a player arriving is shown the
 * bars they are on here.
 */
public final class BossBars {
	private static final Set<ServerBossEvent> ALL = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	private BossBars() {
	}

	public static void register(ServerBossEvent bar) {
		ALL.add(bar);
	}

	static List<ServerBossEvent> showing(ServerPlayer player) {
		List<ServerBossEvent> bars = new ArrayList<>();

		synchronized (ALL) {
			for (ServerBossEvent bar : ALL) {
				if (bar.getPlayers().contains(player)) {
					bars.add(bar);
				}
			}
		}

		return bars;
	}
}
