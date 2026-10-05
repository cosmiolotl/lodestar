package dev.lodecore.shared;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import dev.lodecore.Node;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Stopwatch;
import net.minecraft.world.Stopwatches;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Command storage ({@code /data storage}) and stopwatches ({@code /stopwatch}), kept the same on
 * every node, each a kind of {@link SharedData}.
 *
 * <p>A stopwatch runs on the wall clock, so each node runs its own copy, from what the stopwatch
 * read when it last changed; they agree to within the time a message takes between nodes.
 */
public final class ServerDataSync {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/shared");
	/** How far apart two nodes' copies of a stopwatch can be before one is put right. */
	private static final long STOPWATCH_TOLERANCE_MILLIS = 100;

	private final SharedData data;
	private final Node node;
	private final SharedData.Kind storage;
	private final SharedData.Kind stopwatches;

	ServerDataSync(SharedData data, Node node) {
		this.data = data;
		this.node = node;
		storage = data.register(new Storage());
		stopwatches = data.register(new Watches());
	}

	/**
	 * Loads every namespace of command storage there is in this node's save. The game loads one
	 * when a command first uses it, and what is not loaded would not be offered to other nodes.
	 */
	void start() {
		Path folder = node.server().getWorldPath(LevelResource.DATA);

		if (!Files.isDirectory(folder)) {
			return;
		}

		CommandStorage commandStorage = node.server().getCommandStorage();

		try (Stream<Path> namespaces = Files.list(folder)) {
			namespaces.filter(dir -> Files.isRegularFile(dir.resolve("command_storage.dat")))
					.map(dir -> dir.getFileName().toString())
					.filter(Identifier::isValidNamespace)
					.forEach(namespace -> commandStorage.get(Identifier.fromNamespaceAndPath(namespace, "lodecore")));
		} catch (IOException e) {
			LOGGER.warn("Could not look for command storage in {}: {}", folder, e.toString());
		}
	}

	public void onStorageChanged(Identifier id) {
		data.changed(storage, id.toString());
	}

	public void onStopwatchChanged(Identifier id) {
		data.changed(stopwatches, id.toString());
	}

	private final class Storage extends SharedData.Kind {
		Storage() {
			super("command storage");
		}

		@Override
		protected @Nullable CompoundTag get(String key) {
			CompoundTag tag = node.server().getCommandStorage().get(Identifier.parse(key));
			return tag.isEmpty() ? null : tag.copy();
		}

		/** Empty contents take the key away, as they do for {@code /data}. */
		@Override
		protected void set(String key, @Nullable CompoundTag value) {
			node.server().getCommandStorage().set(Identifier.parse(key), value == null ? new CompoundTag() : value.copy());
		}

		@Override
		protected Collection<String> keys() {
			return node.server().getCommandStorage().keys().map(Identifier::toString).toList();
		}
	}

	private final class Watches extends SharedData.Kind {
		Watches() {
			super("stopwatch");
		}

		@Override
		protected @Nullable CompoundTag get(String key) {
			Stopwatch stopwatch = node.server().getStopwatches().get(Identifier.parse(key));

			if (stopwatch == null) {
				return null;
			}

			CompoundTag tag = new CompoundTag();
			tag.putLong("elapsed", stopwatch.elapsedMilliseconds(Stopwatches.currentTime()));
			return tag;
		}

		@Override
		protected void set(String key, @Nullable CompoundTag value) {
			Stopwatches all = node.server().getStopwatches();
			Identifier id = Identifier.parse(key);

			if (value == null) {
				all.remove(id);
				return;
			}

			long now = Stopwatches.currentTime();
			Stopwatch taken = new Stopwatch(now, value.getLongOr("elapsed", 0));
			Stopwatch stopwatch = all.get(id);

			if (stopwatch == null) {
				all.add(id, taken);
			} else if (Math.abs(stopwatch.elapsedMilliseconds(now) - taken.elapsedMilliseconds(now)) > STOPWATCH_TOLERANCE_MILLIS) {
				all.update(id, previous -> taken);
			}
		}

		@Override
		protected Collection<String> keys() {
			List<Identifier> ids = node.server().getStopwatches().ids();
			return ids.stream().map(Identifier::toString).toList();
		}
	}
}
