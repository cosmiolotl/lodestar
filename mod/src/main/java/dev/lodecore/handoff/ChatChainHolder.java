package dev.lodecore.handoff;

import org.jspecify.annotations.Nullable;

import net.minecraft.network.chat.SignedMessageChain;

/**
 * Implemented by {@code ServerGamePacketListenerImpl} through a mixin: the chain of signed chat
 * messages the player's client is part way along, which the game only keeps inside its decoder.
 */
public interface ChatChainHolder {
	@Nullable SignedMessageChain lodecore$chain();

	void lodecore$setChain(@Nullable SignedMessageChain chain);
}
