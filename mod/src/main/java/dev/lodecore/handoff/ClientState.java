package dev.lodecore.handoff;

import com.google.gson.JsonParser;
import dev.lodecore.Node;
import dev.lodecore.mixin.AbstractContainerMenuAccessor;
import dev.lodecore.mixin.LivingEntityAccessor;
import dev.lodecore.mixin.PlayerChunkSenderAccessor;
import dev.lodecore.mixin.ServerCommonPacketListenerImplAccessor;
import dev.lodecore.mixin.ServerGamePacketListenerImplAccessor;
import dev.lodecore.mixin.ServerPlayerAccessor;
import dev.lodecore.mixin.ServerPlayerGameModeAccessor;
import dev.lodecore.mixin.ServerStatsCounterAccessor;
import dev.lodecore.mixin.StatsCounterAccessor;
import dev.lodecore.storage.SavedAdvancements;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * What a player's client has been told by its node, beyond what the player's saved data has,
 * written down where the player leaves and taken up where they arrive.
 *
 * <p>The client goes on as if nothing happened, so the node it is relayed to next must carry on
 * where the last one stopped: the same ids for its keep-alive, teleport and chat messages, the
 * same chat signatures, the same chunks and entities known to be on the client, the same entries
 * in its player list, and what the player was half-way through doing (breaking a block, drawing
 * a bow). Advancements and stats are each node's own, so they come along too.
 */
final class ClientState {
	private ClientState() {
	}

	/** Writes down a player's state as their client knows it. */
	static byte[] capture(Node node, ServerPlayer player) throws IOException {
		CompoundTag tag = new CompoundTag();
		MinecraftServer server = node.server();
		ServerLevel level = player.level();
		ServerGamePacketListenerImpl listener = player.connection;
		ServerCommonPacketListenerImplAccessor common = (ServerCommonPacketListenerImplAccessor) listener;
		ServerGamePacketListenerImplAccessor game = (ServerGamePacketListenerImplAccessor) listener;

		tag.putInt("EntityId", player.getId());
		tag.putString("Dimension", level.dimension().identifier().toString());
		FriendlyByteBuf information = new FriendlyByteBuf(Unpooled.buffer());
		player.clientInformation().write(information);
		tag.putByteArray("ClientInformation", ByteBufUtil.getBytes(information));

		tag.putByteArray("Advancements", ((SavedAdvancements) player.getAdvancements()).lodecore$savedAdvancements().getBytes(StandardCharsets.UTF_8));
		tag.putByteArray("Stats", ((ServerStatsCounterAccessor) player.getStats()).lodecore$toJson().toString().getBytes(StandardCharsets.UTF_8));

		tag.putBoolean("KeepAlivePending", common.lodecore$keepAlivePending());
		tag.putLong("KeepAliveChallenge", common.lodecore$keepAliveChallenge());
		tag.putLong("KeepAliveAge", Util.getMillis() - common.lodecore$keepAliveTime());
		tag.putInt("Latency", listener.latency());
		tag.putInt("TeleportId", game.lodecore$awaitingTeleport());
		tag.putInt("TeleportTime", game.lodecore$awaitingTeleportTime());
		Vec3 awaiting = game.lodecore$awaitingPosition();

		if (awaiting != null) {
			tag.put("AwaitingPosition", doubles(awaiting.x, awaiting.y, awaiting.z));
		}

		tag.putInt("AckBlockChanges", game.lodecore$ackBlockChangesUpTo());
		HandoffChat.captureChat(tag, listener, game);

		if (player.getChunkTrackingView() instanceof ChunkTrackingView.Positioned view) {
			tag.putIntArray("ChunkView", new int[] {view.center().x(), view.center().z(), view.viewDistance()});
		}

		PlayerChunkSenderAccessor chunks = (PlayerChunkSenderAccessor) listener.chunkSender;
		tag.putLongArray("PendingChunks", chunks.lodecore$pendingChunks().toLongArray());
		tag.putFloat("ChunksPerTick", chunks.lodecore$desiredChunksPerTick());
		tag.putFloat("BatchQuota", chunks.lodecore$batchQuota());
		tag.putInt("UnacknowledgedBatches", chunks.lodecore$unacknowledgedBatches());
		tag.putInt("MaxUnacknowledgedBatches", chunks.lodecore$maxUnacknowledgedBatches());

		IntList seenIds = new IntArrayList();
		LongList seenUuids = new LongArrayList();
		level.getChunkSource().chunkMap.forEachEntityTrackedBy(player, entity -> {
			seenIds.add(entity.getId());
			seenUuids.add(entity.getUUID().getMostSignificantBits());
			seenUuids.add(entity.getUUID().getLeastSignificantBits());
		});
		tag.putIntArray("SeenIds", seenIds.toIntArray());
		tag.putLongArray("SeenUuids", seenUuids.toLongArray());

		LongList listed = new LongArrayList();

		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			listed.add(other.getUUID().getMostSignificantBits());
			listed.add(other.getUUID().getLeastSignificantBits());
		}

		for (ServerPlayer mirror : node.entities().remotePlayerMirrors()) {
			listed.add(mirror.getUUID().getMostSignificantBits());
			listed.add(mirror.getUUID().getLeastSignificantBits());
		}

		tag.putLongArray("Listed", listed.toLongArray());

		tag.putInt("AttackStrength", ((LivingEntityAccessor) player).lodecore$attackStrengthTicker());
		tag.putInt("InvulnerableTime", player.getInvulnerableTime());
		tag.putInt("ContainerCounter", ((ServerPlayerAccessor) player).lodecore$containerCounter());
		tag.putInt("MenuStateId", player.inventoryMenu.getStateId());
		ItemStack carried = player.inventoryMenu.getCarried();

		if (!carried.isEmpty()) {
			RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
			ItemStack.OPTIONAL_STREAM_CODEC.encode(out, carried);
			tag.putByteArray("Carried", ByteBufUtil.getBytes(out));
		}

		Input input = player.getLastClientInput();
		tag.putByte("Input", (byte) ((input.forward() ? 1 : 0) | (input.backward() ? 2 : 0) | (input.left() ? 4 : 0) | (input.right() ? 8 : 0)
				| (input.jump() ? 16 : 0) | (input.shift() ? 32 : 0) | (input.sprint() ? 64 : 0)));

		if (player.isUsingItem()) {
			tag.putBoolean("UsingOffHand", player.getUsedItemHand() == InteractionHand.OFF_HAND);
			tag.putInt("UseRemaining", player.getUseItemRemainingTicks());
		}

		ServerPlayerGameModeAccessor mode = (ServerPlayerGameModeAccessor) player.gameMode;
		tag.putInt("LastSentDestroyState", mode.lodecore$lastSentState());

		if (mode.lodecore$isDestroyingBlock()) {
			tag.putLong("DestroyPos", mode.lodecore$destroyPos().asLong());
			tag.putInt("DestroyAge", mode.lodecore$gameTicks() - mode.lodecore$destroyProgressStart());
		}

		if (mode.lodecore$hasDelayedDestroy()) {
			tag.putLong("DelayedDestroyPos", mode.lodecore$delayedDestroyPos().asLong());
			tag.putInt("DelayedDestroyAge", mode.lodecore$gameTicks() - mode.lodecore$delayedTickStart());
		}

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		NbtIo.writeCompressed(tag, bytes);
		return bytes.toByteArray();
	}

	static CompoundTag read(byte[] state) throws IOException {
		return NbtIo.readCompressed(new ByteArrayInputStream(state), NbtAccounter.unlimitedHeap());
	}

	private static ListTag doubles(double... values) {
		ListTag list = new ListTag();

		for (double value : values) {
			list.add(net.minecraft.nbt.DoubleTag.valueOf(value));
		}

		return list;
	}

	// ---- on the node the player arrives at ----

	/**
	 * Takes up what concerns the player and their connection: their client's settings,
	 * advancements and stats, the state of their connection, and what they were doing.
	 */
	static void restorePlayer(Node node, ServerPlayer player, CompoundTag tag) throws IOException {
		MinecraftServer server = node.server();
		ServerGamePacketListenerImpl listener = player.connection;
		ServerCommonPacketListenerImplAccessor common = (ServerCommonPacketListenerImplAccessor) listener;
		ServerGamePacketListenerImplAccessor game = (ServerGamePacketListenerImplAccessor) listener;

		tag.getByteArray("ClientInformation").ifPresent(bytes -> player.updateOptions(new ClientInformation(new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes)))));

		PlayerAdvancements advancements = player.getAdvancements();
		((SavedAdvancements) advancements).lodecore$restoreAdvancements(server.getAdvancements(),
				new String(tag.getByteArray("Advancements").orElse(new byte[0]), StandardCharsets.UTF_8));
		advancements.setPlayer(player);
		ServerStatsCounter stats = player.getStats();
		((StatsCounterAccessor) stats).lodecore$stats().clear();
		byte[] statsJson = tag.getByteArray("Stats").orElse(new byte[0]);

		if (statsJson.length > 0) {
			stats.parse(server.getFixerUpper(), JsonParser.parseString(new String(statsJson, StandardCharsets.UTF_8)));
		}

		common.lodecore$setKeepAlivePending(tag.getBooleanOr("KeepAlivePending", false));
		common.lodecore$setKeepAliveChallenge(tag.getLongOr("KeepAliveChallenge", 0));
		common.lodecore$setKeepAliveTime(Util.getMillis() - tag.getLongOr("KeepAliveAge", 0));
		common.lodecore$setLatency(tag.getIntOr("Latency", 0));
		game.lodecore$setAwaitingTeleport(tag.getIntOr("TeleportId", 0));
		game.lodecore$setAwaitingTeleportTime(tag.getIntOr("TeleportTime", 0));
		ListTag awaiting = tag.getListOrEmpty("AwaitingPosition");
		game.lodecore$setAwaitingPosition(awaiting.size() == 3 ? new Vec3(awaiting.getDoubleOr(0, 0), awaiting.getDoubleOr(1, 0), awaiting.getDoubleOr(2, 0)) : null);
		game.lodecore$setAckBlockChangesUpTo(tag.getIntOr("AckBlockChanges", -1));
		HandoffChat.restoreChat(server, player, tag, game);

		((LivingEntityAccessor) player).lodecore$setAttackStrengthTicker(tag.getIntOr("AttackStrength", 0));
		player.setInvulnerableTime(tag.getIntOr("InvulnerableTime", 0));
		((ServerPlayerAccessor) player).lodecore$setContainerCounter(tag.getIntOr("ContainerCounter", 0));
		((AbstractContainerMenuAccessor) player.inventoryMenu).lodecore$setStateId(tag.getIntOr("MenuStateId", 0));
		Optional<byte[]> carried = tag.getByteArray("Carried");

		if (carried.isPresent()) {
			RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(carried.get()), player.level().registryAccess());
			player.inventoryMenu.setCarried(ItemStack.OPTIONAL_STREAM_CODEC.decode(in));
		}

		int input = tag.getByteOr("Input", (byte) 0);
		player.setLastClientInput(new Input((input & 1) != 0, (input & 2) != 0, (input & 4) != 0, (input & 8) != 0, (input & 16) != 0, (input & 32) != 0, (input & 64) != 0));

		// The using flag came with the player's synched data; the item it refers to did not.
		tag.getInt("UseRemaining").ifPresent(remaining -> {
			InteractionHand hand = tag.getBooleanOr("UsingOffHand", false) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
			((LivingEntityAccessor) player).lodecore$setUseItem(player.getItemInHand(hand));
			((LivingEntityAccessor) player).lodecore$setUseItemRemaining(remaining);
		});

		ServerPlayerGameModeAccessor mode = (ServerPlayerGameModeAccessor) player.gameMode;
		mode.lodecore$setLastSentState(tag.getIntOr("LastSentDestroyState", -1));
		tag.getLong("DestroyPos").ifPresent(pos -> {
			mode.lodecore$setDestroyingBlock(true);
			mode.lodecore$setDestroyPos(BlockPos.of(pos));
			mode.lodecore$setDestroyProgressStart(mode.lodecore$gameTicks() - tag.getIntOr("DestroyAge", 0));
		});
		tag.getLong("DelayedDestroyPos").ifPresent(pos -> {
			mode.lodecore$setHasDelayedDestroy(true);
			mode.lodecore$setDelayedDestroyPos(BlockPos.of(pos));
			mode.lodecore$setDelayedTickStart(mode.lodecore$gameTicks() - tag.getIntOr("DelayedDestroyAge", 0));
		});
	}

	/**
	 * Takes up what the client has been shown: the chunks it has, the entities in them, and the
	 * players in its list. Whatever this node would show it differently follows, through the
	 * game's own tracking, once the player is placed.
	 */

	static void restoreView(Node node, ServerPlayer player, CompoundTag tag) { HandoffView.restoreView(node, player, tag); }
}
