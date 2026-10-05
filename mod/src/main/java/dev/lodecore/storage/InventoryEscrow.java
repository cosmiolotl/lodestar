package dev.lodecore.storage;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Items in a cursor or temporary menu are part of the player's durable inventory. */
public final class InventoryEscrow {
	private static final String KEY = "LodecoreInventoryEscrow";
	private InventoryEscrow() { }

	public static void capture(ServerPlayer player, CompoundTag tag) {
		List<ItemStack> items = new ArrayList<>();
		if (!player.containerMenu.getCarried().isEmpty()) items.add(player.containerMenu.getCarried());
		collect(player.inventoryMenu, items);
		if (player.containerMenu != player.inventoryMenu) collect(player.containerMenu, items);
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
		try {
			buffer.writeVarInt(items.size());
			for (ItemStack item : items) ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, item);
			tag.putByteArray(KEY, ByteBufUtil.getBytes(buffer));
		} finally { buffer.release(); }
	}

	private static void collect(AbstractContainerMenu menu, List<ItemStack> items) {
		// Only menus whose inputs are transient. Chest, hopper, horse and other persistent
		// containers are already captured in the world record and must not be counted twice.
		if (!(menu instanceof AbstractCraftingMenu || menu instanceof ItemCombinerMenu
				|| menu instanceof EnchantmentMenu || menu instanceof LoomMenu || menu instanceof StonecutterMenu
				|| menu instanceof CartographyTableMenu || menu instanceof MerchantMenu
				|| menu instanceof GrindstoneMenu || menu instanceof BeaconMenu)) return;
		for (Slot slot : menu.slots) {
			if (slot.container instanceof Inventory || slot.container instanceof BlockEntity
					|| slot.container instanceof ResultContainer || slot instanceof ResultSlot
					|| slot instanceof MerchantResultSlot || slot.getItem().isEmpty()) continue;
			items.add(slot.getItem());
		}
	}

	public static void restore(ServerPlayer player, CompoundTag tag) {
		byte[] data = tag.getByteArray(KEY).orElse(new byte[0]);
		if (data.length == 0) return;
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), player.registryAccess());
		try {
			int count = buffer.readVarInt();
			if (count < 0 || count > 256) throw new IllegalStateException("Invalid saved inventory escrow");
			for (int index = 0; index < count; index++) {
				ItemStack item = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
				player.getInventory().placeItemBackInInventory(item, net.minecraft.util.Prediction.SERVER_ONLY);
			}
		} finally { buffer.release(); }
	}
}
