package dev.lodecore.mixin;

import java.util.Optional;

import com.mojang.datafixers.DataFixer;
import dev.lodecore.replication.Hooks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;

/** While this node is part of a cluster, players' data is lodestar's. */
@Mixin(PlayerDataStorage.class)
abstract class PlayerDataStorageMixin {
	@Shadow
	@Final
	protected DataFixer fixerUpper;

	@Inject(method = "load(Lnet/minecraft/server/players/NameAndId;)Ljava/util/Optional;", at = @At("HEAD"), cancellable = true)
	private void lodecore$loadWhatLodestarLent(NameAndId nameAndId, CallbackInfoReturnable<Optional<CompoundTag>> cir) {
		CompoundTag lent = Hooks.lentPlayerData(nameAndId.id());

		if (lent == null) {
			cir.setReturnValue(Optional.empty());
			return;
		}
		cir.setReturnValue(Optional.of(DataFixTypes.PLAYER.updateToCurrentVersion(this.fixerUpper, lent, NbtUtils.getDataVersion(lent))));
	}

	@Inject(method = "save", at = @At("HEAD"), cancellable = true)
	private void lodecore$saveToLodestar(Player player, CallbackInfo ci) {
		Hooks.onPlayerSaved(player);
		if (dev.lodecore.storage.WorldStorage.enabled()) ci.cancel();
	}
}
