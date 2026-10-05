package dev.lodecore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;

@Mixin(CompoundContainer.class)
public interface CompoundContainerAccessor {
	@Accessor("container1")
	Container lodecore$first();

	@Accessor("container2")
	Container lodecore$second();
}
