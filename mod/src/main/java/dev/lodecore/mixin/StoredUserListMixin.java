package dev.lodecore.mixin;

import java.util.List;
import java.util.Map;

import dev.lodecore.replication.Hooks;
import dev.lodecore.shared.SharedData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.players.StoredUserEntry;
import net.minecraft.server.players.StoredUserList;

/** Notes entries added to and taken off the operator, whitelist and ban lists. */
@Mixin(StoredUserList.class)
abstract class StoredUserListMixin {
	@Shadow
	@Final
	private Map<String, ?> map;

	@Shadow
	protected abstract String getKeyForUser(Object user);

	@Inject(method = "add", at = @At("RETURN"))
	private void lodecore$onAdded(StoredUserEntry<?> entry, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) {
			onChanged(this.getKeyForUser(entry.getUser()));
		}
	}

	@Inject(method = "remove(Ljava/lang/Object;)Z", at = @At("RETURN"))
	private void lodecore$onRemoved(Object user, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) {
			onChanged(this.getKeyForUser(user));
		}
	}

	@Inject(method = "clear", at = @At("HEAD"))
	private void lodecore$onCleared(CallbackInfo ci) {
		for (String key : List.copyOf(this.map.keySet())) {
			onChanged(key);
		}
	}

	private void onChanged(String key) {
		SharedData shared = Hooks.shared();

		if (shared != null) {
			shared.userLists().onChanged((StoredUserList<?, ?>) (Object) this, key);
		}
	}
}
