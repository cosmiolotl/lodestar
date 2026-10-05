package dev.lodecore.mixin;

import java.util.ArrayList;
import java.util.List;

import dev.lodecore.replication.ReplicatedData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;

@Mixin(SynchedEntityData.class)
abstract class SynchedEntityDataMixin implements ReplicatedData {
	@Shadow @Final private net.minecraft.network.syncher.SyncedDataHolder entity;
	@Shadow
	@Final
	private SynchedEntityData.DataItem<?>[] itemsById;

	/** Ids of the values changed since replication last took them, as a bit set. Ids fit in a byte. */
	@Unique
	private long[] lodecore$changed;

	@Shadow
	public abstract <T> void set(EntityDataAccessor<T> accessor, T value);

	@Inject(
			method = "set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;Z)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/network/syncher/SynchedEntityData$DataItem;setDirty(Z)V"))
	private void lodecore$noteChange(EntityDataAccessor<?> accessor, Object value, boolean forceDirty, CallbackInfo ci) {
		if (lodecore$changed == null) {
			lodecore$changed = new long[4];
		}

		lodecore$changed[accessor.id() >>> 6] |= 1L << accessor.id();
		if (entity instanceof net.minecraft.world.entity.Entity changed) dev.lodecore.ChangeHooks.metadata(changed);
	}

	@Override
	public List<SynchedEntityData.DataValue<?>> lodecore$takeChanged() {
		if (lodecore$changed == null) {
			return List.of();
		}

		List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();

		for (int word = 0; word < lodecore$changed.length; word++) {
			long bits = lodecore$changed[word];

			while (bits != 0) {
				int id = (word << 6) + Long.numberOfTrailingZeros(bits);
				bits &= bits - 1;

				if (id < itemsById.length) {
					values.add(itemsById[id].value());
				}
			}
		}

		lodecore$changed = null;
		return values;
	}

	@Override
	public void lodecore$assign(SynchedEntityData.DataValue<?> value) {
		if (value.id() < 0 || value.id() >= itemsById.length) {
			return;
		}

		EntityDataAccessor<?> accessor = itemsById[value.id()].getAccessor();

		if (accessor.serializer().equals(value.serializer())) {
			lodecore$set(accessor, value.value());
		}
	}

	@Unique
	@SuppressWarnings("unchecked")
	private <T> void lodecore$set(EntityDataAccessor<T> accessor, Object value) {
		this.set(accessor, (T) value);
	}
}
