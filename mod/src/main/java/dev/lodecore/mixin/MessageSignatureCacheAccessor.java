package dev.lodecore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.MessageSignatureCache;

@Mixin(MessageSignatureCache.class)
public interface MessageSignatureCacheAccessor {
	@Accessor("entries")
	MessageSignature[] lodecore$entries();
}
