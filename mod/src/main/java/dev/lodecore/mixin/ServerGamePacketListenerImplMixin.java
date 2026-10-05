package dev.lodecore.mixin;

import java.util.UUID;

import dev.lodecore.handoff.ChatChainHolder;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.chat.SignedMessageChain;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin implements ChatChainHolder {
	@Unique
	private @Nullable SignedMessageChain lodecore$chain;

	@Override
	public @Nullable SignedMessageChain lodecore$chain() {
		return this.lodecore$chain;
	}

	@Override
	public void lodecore$setChain(@Nullable SignedMessageChain chain) {
		this.lodecore$chain = chain;
	}

	/** Makes the decoder as the game does, keeping hold of the chain it reads along. */
	@Redirect(
			method = "resetPlayerChatState",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/network/chat/RemoteChatSession;createMessageDecoder(Ljava/util/UUID;)Lnet/minecraft/network/chat/SignedMessageChain$Decoder;"))
	private SignedMessageChain.Decoder lodecore$keepChain(RemoteChatSession session, UUID profileId) {
		SignedMessageChain chain = new SignedMessageChain(profileId, session.sessionId());
		this.lodecore$chain = chain;
		return chain.decoder(session.profilePublicKey());
	}
}
