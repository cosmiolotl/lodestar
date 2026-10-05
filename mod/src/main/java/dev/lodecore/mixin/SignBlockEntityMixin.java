package dev.lodecore.mixin;

import java.util.List;

import dev.lodecore.replication.Hooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;

@Mixin(SignBlockEntity.class)
abstract class SignBlockEntityMixin {
	/** Writing on a sign another node is the authority for waits for custody of it. */
	@Inject(method = "updateSignText", at = @At("HEAD"), cancellable = true)
	private void lodecore$editWithCustody(Player player, SignTextSlot slot, List<FilteredText> lines, CallbackInfo ci) {
		if (Hooks.updateSignText((SignBlockEntity) (Object) this, player, slot, lines)) {
			ci.cancel();
		}
	}
}
