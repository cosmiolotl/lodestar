package dev.lodecore;

import java.util.function.Supplier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.lodecore.mixin.RaidsAccessor;
import dev.lodecore.net.Wire;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Operator commands for looking into and exercising a node:
 *
 * <ul>
 * <li>{@code /lodecore owner <x> <z>}: which node simulates a block column.
 * <li>{@code /lodecore holder <pos>}: which node has custody of a block entity, if any.
 * <li>{@code /lodecore id <entity>}: an entity's id, which is the same on every node.
 * <li>{@code /lodecore move <player> <node>}: asks lodestar to move a player homed here to another
 * node, by its number, without their client noticing.
 * <li>{@code /lodecore raids}: the raids in this dimension, and which node runs each.
 * <li>{@code /lodecore freeze} and {@code /lodecore unfreeze}: freezes this node alone, as
 * {@code /tick freeze} would on a single server, for tests that need one node to stand still.
 * {@code /tick} itself freezes the whole cluster.
 * <li>{@code /lodecore act <player> ...}: makes a player homed here do what a click of theirs would:
 * {@code interact} with or {@code attack} an entity, {@code use} or {@code break} a block,
 * {@code click} or shift-click ({@code take}) a slot of the menu they have open, or {@code close} it. These call the
 * same code a player's client reaches, which is how the live tests drive players without a client.
 * </ul>
 */
final class LodecoreCommand {
	private LodecoreCommand() {
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<Node> node) {
		dispatcher.register(
				Commands.literal("lodecore")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("owner")
								.then(Commands.argument("pos", ColumnPosArgument.columnPos())
										.executes(c -> owner(c.getSource(), ColumnPosArgument.getColumnPos(c, "pos"), node.get()))))
						.then(Commands.literal("holder")
								.then(Commands.argument("pos", BlockPosArgument.blockPos())
										.executes(c -> holder(c.getSource(), BlockPosArgument.getBlockPos(c, "pos"), node.get()))))
						.then(Commands.literal("id")
								.then(Commands.argument("entity", EntityArgument.entity())
										.executes(c -> id(c.getSource(), EntityArgument.getEntity(c, "entity")))))
						.then(Commands.literal("move")
								.then(Commands.argument("player", EntityArgument.player())
										.then(Commands.argument("node", IntegerArgumentType.integer(1))
												.executes(c -> move(c.getSource(), EntityArgument.getPlayer(c, "player"), IntegerArgumentType.getInteger(c, "node"), node.get())))))
						.then(Commands.literal("raids")
								.executes(c -> raids(c.getSource(), node.get())))
						.then(Commands.literal("freeze")
								.executes(c -> freeze(c.getSource(), true, node.get())))
						.then(Commands.literal("unfreeze")
								.executes(c -> freeze(c.getSource(), false, node.get())))
						.then(Commands.literal("act")
								.then(Commands.argument("player", EntityArgument.player())
										.then(Commands.literal("interact")
												.then(Commands.argument("target", EntityArgument.entity())
														.executes(c -> interact(c.getSource(), EntityArgument.getPlayer(c, "player"), EntityArgument.getEntity(c, "target")))))
										.then(Commands.literal("attack")
												.then(Commands.argument("target", EntityArgument.entity())
														.executes(c -> attack(c.getSource(), EntityArgument.getPlayer(c, "player"), EntityArgument.getEntity(c, "target")))))
										.then(Commands.literal("use")
												.then(Commands.argument("pos", BlockPosArgument.blockPos())
														.executes(c -> use(c.getSource(), EntityArgument.getPlayer(c, "player"), BlockPosArgument.getBlockPos(c, "pos")))))
										.then(Commands.literal("break")
												.then(Commands.argument("pos", BlockPosArgument.blockPos())
														.executes(c -> destroy(c.getSource(), EntityArgument.getPlayer(c, "player"), BlockPosArgument.getBlockPos(c, "pos")))))
										.then(Commands.literal("take")
												.then(Commands.argument("slot", IntegerArgumentType.integer(0))
														.executes(c -> take(c.getSource(), EntityArgument.getPlayer(c, "player"), IntegerArgumentType.getInteger(c, "slot")))))
										.then(Commands.literal("click")
												.then(Commands.argument("slot", IntegerArgumentType.integer(0))
														.executes(c -> click(c.getSource(), EntityArgument.getPlayer(c, "player"), IntegerArgumentType.getInteger(c, "slot")))))
										.then(Commands.literal("close")
												.executes(c -> close(c.getSource(), EntityArgument.getPlayer(c, "player")))))));
	}

	private static int owner(CommandSourceStack source, ColumnPos column, Node node) {
		if (node == null) {
			source.sendFailure(Component.literal("This server is not a node"));
			return 0;
		}

		ChunkPos chunk = column.toChunkPos();
		String who;
		int owner = node.ownerOf(source.getLevel().dimension(), chunk.pack());

		if (!node.isConnected()) {
			who = "this node, which is not connected to lodestar";
		} else if (owner == Node.NO_NODE) {
			who = "nobody yet";
		} else if (owner == node.nodeId()) {
			who = "this node (#" + owner + ")";
		} else {
			who = "node #" + owner;
		}

		String message = "Chunk " + chunk.x() + " " + chunk.z() + " is in region " + (chunk.x() >> Wire.REGION_SHIFT) + " "
				+ (chunk.z() >> Wire.REGION_SHIFT) + ", simulated by " + who
				+ (node.hasLoaded(source.getLevel(), chunk.pack()) ? "; loaded here" : "; not loaded here")
				+ (node.blocks().isSynced(source.getLevel(), chunk) ? ", in step with the owner" : "")
				+ (node.isNeededElsewhere(source.getLevel(), chunk.pack()) ? ", held for another node" : "");
		source.sendSuccess(() -> Component.literal(message), false);
		return owner;
	}

	private static int holder(CommandSourceStack source, BlockPos pos, Node node) {
		if (node == null) {
			source.sendFailure(Component.literal("This server is not a node"));
			return 0;
		}

		int holder = node.custody().holderOf(source.getLevel(), pos);
		String who = holder == Node.NO_NODE ? "nobody: its region's owner has it" : holder == node.nodeId() ? "this node (#" + holder + ")" : "node #" + holder;
		return report(source, "The block entity at " + pos.toShortString() + " is in the custody of " + who);
	}

	private static int id(CommandSourceStack source, Entity entity) {
		report(source, entity.getPlainTextName() + " has id " + entity.getId());
		return entity.getId();
	}

	private static int move(CommandSourceStack source, ServerPlayer player, int to, Node node) {
		if (node == null || !node.isConnected()) {
			source.sendFailure(Component.literal("This node is not connected to lodestar"));
			return 0;
		}

		if (to == node.nodeId()) {
			source.sendFailure(Component.literal(player.getPlainTextName() + " is on node #" + to + " already"));
			return 0;
		}

		node.send(Wire.handoffRequest(player.getUUID(), to));
		return report(source, "Asked lodestar to move " + player.getPlainTextName() + " to node #" + to);
	}

	private static int raids(CommandSourceStack source, Node node) {
		Int2ObjectMap<Raid> raids = ((RaidsAccessor) source.getLevel().getRaids()).lodecore$raidMap();
		StringBuilder message = new StringBuilder(raids.size() + " raid(s)");

		for (Int2ObjectMap.Entry<Raid> entry : raids.int2ObjectEntrySet()) {
			Raid raid = entry.getValue();
			int runner = node == null ? Node.NO_NODE : node.ownerOf(source.getLevel().dimension(), ChunkPos.pack(raid.getCenter()));
			String status = raid.isStopped() ? "stopped" : raid.isVictory() ? "won" : raid.isLoss() ? "lost" : raid.isActive() ? "active" : "inactive";
			message.append("; #").append(entry.getIntKey())
					.append(" at ").append(raid.getCenter().toShortString())
					.append(", ").append(status)
					.append(", wave ").append(raid.getGroupsSpawned())
					.append(", run by ").append(node == null || !node.isConnected() || node.raids().runsHere(source.getLevel(), raid) ? "this node" : "node #" + runner);
		}

		return report(source, message.toString());
	}

	private static int freeze(CommandSourceStack source, boolean frozen, Node node) {
		if (node == null) {
			source.sendFailure(Component.literal("This server is not a node"));
			return 0;
		}

		node.worldState().freezeHere(frozen);
		return report(source, frozen ? "This node alone is frozen" : "This node runs with the cluster again");
	}

	private static int report(CommandSourceStack source, String what) {
		source.sendSuccess(() -> Component.literal(what), false);
		return 1;
	}

	private static int interact(CommandSourceStack source, ServerPlayer player, Entity target) {
		InteractionResult result = player.interactOn(target, InteractionHand.MAIN_HAND, target.position());
		return report(source, player.getPlainTextName() + " interacted with " + target.getPlainTextName() + ": " + describe(result));
	}

	private static int attack(CommandSourceStack source, ServerPlayer player, Entity target) {
		player.attack(target);
		return report(source, player.getPlainTextName() + " attacked " + target.getPlainTextName());
	}

	private static int use(CommandSourceStack source, ServerPlayer player, BlockPos pos) {
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
		InteractionResult result = player.gameMode.useItemOn(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
		return report(source, player.getPlainTextName() + " used the block at " + pos.toShortString() + ": " + describe(result));
	}

	private static int destroy(CommandSourceStack source, ServerPlayer player, BlockPos pos) {
		boolean done = player.gameMode.destroyBlock(pos);
		return report(source, player.getPlainTextName() + " broke the block at " + pos.toShortString() + ": " + (done ? "done" : "failed"));
	}

	private static int take(CommandSourceStack source, ServerPlayer player, int slot) throws CommandSyntaxException {
		if (player.containerMenu == player.inventoryMenu || slot >= player.containerMenu.slots.size()) {
			source.sendFailure(Component.literal(player.getPlainTextName() + " has no menu open with slot " + slot));
			return 0;
		}

		ItemStack moved = player.containerMenu.getSlot(slot).getItem().copy();
		player.containerMenu.quickMoveStack(player, slot);
		player.containerMenu.broadcastChanges();
		return report(source, player.getPlainTextName() + " shift-clicked " + moved.getCount() + " " + moved.getItemName().getString());
	}

	private static int close(CommandSourceStack source, ServerPlayer player) {
		player.closeContainer();
		return report(source, player.getPlainTextName() + " closed their menu");
	}

	private static int click(CommandSourceStack source, ServerPlayer player, int slot) {
		if (slot >= player.containerMenu.slots.size()) return 0;
		player.containerMenu.clicked(slot, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, player);
		player.containerMenu.broadcastChanges();
		ItemStack carried = player.containerMenu.getCarried();
		return report(source, player.getPlainTextName() + " clicked slot " + slot + "; cursor: " + carried.getCount() + " " + carried.getItemName().getString());
	}

	private static String describe(InteractionResult result) {
		return result instanceof InteractionResult.Success ? "done" : result == InteractionResult.FAIL ? "waiting or refused" : "nothing happened";
	}
}
