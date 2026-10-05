package dev.lodecore.replication;

import java.util.ArrayList;
import java.util.List;

import dev.lodecore.Node;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What a player homed here does to objects this node is not the authority for: each action is
 * held back until this node has custody of what it acts on, and then done as the game would.
 *
 * <p>Hitting a mob or a player is the exception: that damage is sent to the target's authority,
 * which is cheaper than taking custody of a mob for every hit; see {@link EntityReplication}.
 */
public final class Interactions {
	private final Node node;

	public Interactions(Node node) {
		this.node = node;
	}

	/** Whether a player is one homed here, rather than the mirror of a remote player. */
	private boolean isLocal(Player player) {
		return player instanceof ServerPlayer serverPlayer && !node.entities().isRemotePlayer(serverPlayer);
	}

	/**
	 * Right-clicking an entity: trading with a villager, shearing a sheep, mounting a horse,
	 * putting an item in a frame or on an armor stand.
	 *
	 * @return what to answer instead, if the interaction waits for custody
	 */
	public @Nullable InteractionResult interact(Player player, Entity target, InteractionHand hand, Vec3 location) {
		if (!isLocal(player) || target instanceof Player) {
			return null;
		}

		ServerPlayer serverPlayer = (ServerPlayer) player;
		boolean held = node.custody().withCustody(serverPlayer, serverPlayer.level(), List.of(ObjectKey.of(target)), false, () -> {
			if (!target.isRemoved()) {
				ItemStack used = serverPlayer.getItemInHand(hand).copy();
				swingIfDone(serverPlayer, hand, used, serverPlayer.interactOn(target, hand, location));
			}
		}, null);
		return held ? InteractionResult.FAIL : null;
	}

	/**
	 * Hitting an entity that is not a mob or a player: breaking a boat or a minecart, knocking an
	 * item out of a frame, punching an armor stand.
	 *
	 * @return whether the attack waits for custody
	 */
	public boolean attack(Player player, Entity target) {
		if (!isLocal(player) || target instanceof Mob || target instanceof Player) {
			return false;
		}

		ServerPlayer serverPlayer = (ServerPlayer) player;
		return node.custody().withCustody(serverPlayer, serverPlayer.level(), List.of(ObjectKey.of(target)), false, () -> {
			if (!target.isRemoved()) {
				serverPlayer.attack(target);
			}
		}, null);
	}

	/**
	 * Right-clicking a block that has a block entity: opening a chest or a furnace, putting a
	 * disc in a jukebox, taking a book from a lectern. A double chest needs custody of both halves.
	 *
	 * @return what to answer instead, if the use waits for custody
	 */
	public @Nullable InteractionResult useItemOn(ServerPlayer player, ServerLevel level, InteractionHand hand, BlockHitResult hit) {
		if (!isLocal(player)) {
			return null;
		}

		List<ObjectKey> keys = blockEntityKeys(level, hit.getBlockPos());

		if (keys.isEmpty()) {
			return null;
		}

		boolean held = node.custody().withCustody(player, level, keys, false, () -> {
			ItemStack used = player.getItemInHand(hand).copy();
			swingIfDone(player, hand, used, player.gameMode.useItemOn(player, level, player.getItemInHand(hand), hand, hit));
		}, null);
		return held ? InteractionResult.FAIL : null;
	}

	/**
	 * Breaking a block that has a block entity. What it drops comes from what is in it, so it is
	 * broken where that is known: here, once this node has custody of it.
	 *
	 * @return whether the breaking waits for custody
	 */
	public boolean destroyBlock(ServerPlayer player, BlockPos pos) {
		ServerLevel level = player.level();

		if (!isLocal(player) || level.getBlockEntity(pos) == null) {
			return false;
		}

		return node.custody().withCustody(
				player, level, List.of(ObjectKey.of(pos)), false,
				() -> player.gameMode.destroyBlock(pos),
				// The player's client already shows the block gone; put it back.
				() -> player.connection.send(new ClientboundBlockUpdatePacket(level, pos)));
	}

	/**
	 * Writing on a sign. The right to edit a sign, given when it is placed or clicked, is not
	 * part of the sign's saved state, so it is given again once the sign is here.
	 *
	 * @return whether the edit waits for custody
	 */
	public boolean updateSignText(SignBlockEntity sign, Player player, SignTextSlot slot, List<FilteredText> lines) {
		if (!isLocal(player) || !(sign.getLevel() instanceof ServerLevel level)) {
			return false;
		}

		ServerPlayer serverPlayer = (ServerPlayer) player;
		BlockPos pos = sign.getBlockPos();
		return node.custody().withCustody(serverPlayer, level, List.of(ObjectKey.of(pos)), false, () -> {
			if (level.getBlockEntity(pos) instanceof SignBlockEntity here) {
				here.setAllowedPlayerEditor(serverPlayer.getUUID());
				here.updateSignText(serverPlayer, slot, lines);
			}
		}, null);
	}

	/**
	 * A player walking into an item, an arrow or an experience orb. A mirror is never picked up
	 * as it is: the player's node takes custody of it first, so that two players on different
	 * nodes cannot both pick up the same item.
	 *
	 * @return whether the pickup is not to happen now
	 */
	public boolean touch(Entity touched, Player player) {
		if (!isLocal(player) || !(touched.level() instanceof ServerLevel level) || node.entities().isAuthority(level, touched)) {
			return false;
		}

		ServerPlayer serverPlayer = (ServerPlayer) player;

		if (worthClaiming(touched, serverPlayer)) {
			node.custody().withCustody(serverPlayer, level, List.of(ObjectKey.of(touched)), true, () -> {
				if (!touched.isRemoved()) {
					touched.playerTouch(serverPlayer);
				}
			}, null);
		}

		return true;
	}

	/** Swings the player's arm for an action that went through, as the game does for a click. */
	private static void swingIfDone(ServerPlayer player, InteractionHand hand, ItemStack used, InteractionResult result) {
		if (result instanceof InteractionResult.Success success && success.shouldSwing()) {
			player.swingAndResetAttackStrength(hand, used.getInteractAnimation(), true);
		}
	}

	/** Whether the player could take any of it now, as far as this node's copy says. */
	private static boolean worthClaiming(Entity touched, ServerPlayer player) {
		return switch (touched) {
			case ItemEntity item -> player.getInventory().getSlotWithRemainingSpace(item.getItem()) >= 0
					|| player.getInventory().getFreeSlot() >= 0;
			case ExperienceOrb ignored -> player.takeXpDelay == 0;
			default -> true;
		};
	}

	/** The block entity at a position and, for half of a double chest, the other half. */
	private static List<ObjectKey> blockEntityKeys(ServerLevel level, BlockPos pos) {
		BlockEntity blockEntity = level.getBlockEntity(pos);
		List<ObjectKey> keys = new ArrayList<>(2);

		if (blockEntity == null) {
			return keys;
		}

		keys.add(ObjectKey.of(pos));
		BlockState state = blockEntity.getBlockState();

		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			BlockPos other = ChestBlock.getConnectedBlockPos(pos, state);

			if (level.getBlockEntity(other) != null) {
				keys.add(ObjectKey.of(other));
			}
		}

		return keys;
	}
}
