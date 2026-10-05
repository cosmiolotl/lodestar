package dev.lodecore.mixin;

import it.unimi.dsi.fastutil.objects.ObjectList;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.chat.LastSeenMessagesValidator;
import net.minecraft.network.chat.LastSeenTrackedEntry;
import net.minecraft.network.chat.MessageSignature;

@Mixin(LastSeenMessagesValidator.class)
public interface LastSeenMessagesValidatorAccessor {
	@Accessor("trackedMessages")
	ObjectList<@Nullable LastSeenTrackedEntry> lodecore$trackedMessages();

	@Accessor("lastPendingMessage")
	@Nullable MessageSignature lodecore$lastPendingMessage();

	@Accessor("lastPendingMessage")
	void lodecore$setLastPendingMessage(@Nullable MessageSignature signature);
}
