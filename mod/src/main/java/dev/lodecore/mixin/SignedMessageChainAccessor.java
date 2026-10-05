package dev.lodecore.mixin;

import java.time.Instant;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.chat.SignedMessageChain;
import net.minecraft.network.chat.SignedMessageLink;

@Mixin(SignedMessageChain.class)
public interface SignedMessageChainAccessor {
	@Accessor("nextLink")
	@Nullable SignedMessageLink lodecore$nextLink();

	@Accessor("nextLink")
	void lodecore$setNextLink(@Nullable SignedMessageLink link);

	@Accessor("lastTimeStamp")
	Instant lodecore$lastTimeStamp();

	@Accessor("lastTimeStamp")
	void lodecore$setLastTimeStamp(Instant timeStamp);
}
