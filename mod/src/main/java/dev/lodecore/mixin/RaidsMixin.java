package dev.lodecore.mixin;

import dev.lodecore.replication.Hooks;
import dev.lodecore.replication.RaidSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;

/** A raid is run by the node that simulates where it is; see {@link RaidSync}. */
@Mixin(Raids.class)
abstract class RaidsMixin {
	@Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/raid/Raid;tick(Lnet/minecraft/server/level/ServerLevel;)V"))
	private void lodecore$tickRaid(Raid raid, ServerLevel level) {
		RaidSync raids = Hooks.raids();

		if (raids == null) {
			raid.tick(level);
		} else {
			raids.tick(level, raid);
		}
	}

	@Inject(method = "createOrExtendRaid", at = @At("HEAD"), cancellable = true)
	private void lodecore$forwardOmen(ServerPlayer player, BlockPos pos, CallbackInfoReturnable<Raid> cir) {
		RaidSync raids = Hooks.raids();

		if (raids != null && raids.forwardOmen(player, pos)) {
			cir.setReturnValue(player.level().getRaidAt(pos));
		}
	}

	@Inject(method = "getUniqueId", at = @At("HEAD"), cancellable = true)
	private void lodecore$clusterId(CallbackInfoReturnable<Integer> cir) {
		RaidSync raids = Hooks.raids();
		int id = raids == null ? -1 : raids.nextId((Raids) (Object) this);

		if (id >= 0) {
			cir.setReturnValue(id);
		}
	}
}
