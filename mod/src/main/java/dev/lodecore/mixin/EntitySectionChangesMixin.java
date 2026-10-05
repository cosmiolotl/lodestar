package dev.lodecore.mixin;

import dev.lodecore.storage.DirtyEntityStorage;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.function.Consumer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PersistentEntitySectionManager.class)
abstract class EntitySectionChangesMixin<T extends EntityAccess> implements DirtyEntityStorage {
	@Shadow @Final private EntityPersistentStorage<T> permanentStorage;
	@Shadow @Final private Long2ObjectMap<Visibility> chunkVisibility;
	@Shadow public abstract void processPendingLoads();
	@Shadow protected abstract boolean storeChunkSections(long chunk, Consumer<T> visitor);
	@Shadow protected abstract boolean processChunkUnload(long chunk);
	@Unique private final LongSet lodecore$dirty = new LongOpenHashSet();

	@Override public void lodecore$dirty(long chunk) { lodecore$dirty.add(chunk); }

	@Inject(method = "addEntity", at = @At("RETURN"))
	private void lodecore$added(T entity, boolean loaded, CallbackInfoReturnable<Boolean> ci) {
		if (ci.getReturnValueZ()) lodecore$dirty.add(ChunkPos.pack(entity.blockPosition()));
	}
	@Inject(method = "requestChunkLoad", at = @At("HEAD"))
	private void lodecore$pending(long chunk, CallbackInfo ci) { lodecore$dirty.add(chunk); }

	@Override public void lodecore$capture() {
		processPendingLoads();
		LongSet pending = new LongOpenHashSet(lodecore$dirty);
		lodecore$dirty.clear();
		// Preserve vanilla's load-before-store and hidden-section unloading rules, but only
		// revisit changed columns. flush(false) also runs the entity deserializer queue.
		while (!pending.isEmpty()) {
			permanentStorage.flush(false);
			processPendingLoads();
			pending.removeIf((long chunk) -> chunkVisibility.get(chunk) == Visibility.HIDDEN
					? processChunkUnload(chunk) : storeChunkSections(chunk, entity -> { }));
		}
		permanentStorage.flush(true);
	}
}
