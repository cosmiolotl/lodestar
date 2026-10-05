package dev.lodecore.mixin;

import dev.lodecore.storage.DirtyEntityStorage;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback")
abstract class EntitySectionCallbackMixin {
	@Shadow @Final private PersistentEntitySectionManager<?> this$0;
	@Shadow @Final private EntityAccess entity;
	@Shadow private long currentSectionKey;

	@Inject(method = {"onMove", "onRemove"}, at = @At("HEAD"))
	private void lodecore$sectionChanged(CallbackInfo ci) {
		DirtyEntityStorage storage = (DirtyEntityStorage) this$0;
		storage.lodecore$dirty(ChunkPos.pack(SectionPos.x(currentSectionKey), SectionPos.z(currentSectionKey)));
		storage.lodecore$dirty(ChunkPos.pack(entity.blockPosition()));
	}
}
