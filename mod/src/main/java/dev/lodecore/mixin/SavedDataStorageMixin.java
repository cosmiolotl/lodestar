package dev.lodecore.mixin;

import java.io.IOException;
import com.mojang.datafixers.DataFixer;
import dev.lodecore.storage.WorldStorage;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Minecraft's named saved data, including maps and mod-defined global data. */
@Mixin(SavedDataStorage.class)
abstract class SavedDataStorageMixin {
	@Shadow @Final private HolderLookup.Provider registries;
	@Shadow @Final private DataFixer fixerUpper;

	@Inject(method = "readSavedData", at = @At("HEAD"), cancellable = true)
	private <T extends SavedData> void lodecore$load(SavedDataType<T> type, CallbackInfoReturnable<T> ci) throws IOException {
		WorldStorage.Loaded loaded = WorldStorage.loadGlobal(type.id().toString());
		if (!loaded.present()) return;
		if (loaded.data() == null) { ci.setReturnValue(null); return; }
		CompoundTag tag = type.dataFixType().updateToCurrentVersion(fixerUpper, loaded.data(), NbtUtils.getDataVersion(loaded.data(), 1343));
		ci.setReturnValue(type.codec().parse(registries.createSerializationContext(NbtOps.INSTANCE), tag.get("data")).getOrThrow());
	}

	@Inject(method = "tryWrite", at = @At("HEAD"), cancellable = true)
	private void lodecore$save(SavedDataType<?> type, CompoundTag tag, CallbackInfo ci) {
		try {
			WorldStorage.saveGlobal(type.id().toString(), tag);
			if (WorldStorage.enabled()) ci.cancel();
		} catch (IOException e) {
			throw new IllegalStateException("Cannot save authoritative global data " + type.id(), e);
		}
	}
}
