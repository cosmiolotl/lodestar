package dev.lodecore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
	@Accessor("useItem")
	void lodecore$setUseItem(ItemStack item);

	@Accessor("useItemRemaining")
	void lodecore$setUseItemRemaining(int ticks);

	@Accessor("attackStrengthTicker")
	int lodecore$attackStrengthTicker();

	@Accessor("attackStrengthTicker")
	void lodecore$setAttackStrengthTicker(int ticks);
}
