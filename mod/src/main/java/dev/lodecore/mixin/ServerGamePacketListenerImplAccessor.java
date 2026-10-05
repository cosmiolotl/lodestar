package dev.lodecore.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.chat.LastSeenMessagesValidator;
import net.minecraft.network.chat.MessageSignatureCache;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.chat.SignedMessageChain;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;

@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerImplAccessor {
	@Accessor("awaitingPositionFromClient")
	@Nullable Vec3 lodecore$awaitingPosition();

	@Accessor("awaitingPositionFromClient")
	void lodecore$setAwaitingPosition(@Nullable Vec3 position);

	@Accessor("awaitingTeleport")
	int lodecore$awaitingTeleport();

	@Accessor("awaitingTeleport")
	void lodecore$setAwaitingTeleport(int id);

	@Accessor("awaitingTeleportTime")
	int lodecore$awaitingTeleportTime();

	@Accessor("awaitingTeleportTime")
	void lodecore$setAwaitingTeleportTime(int time);

	@Accessor("ackBlockChangesUpTo")
	int lodecore$ackBlockChangesUpTo();

	@Accessor("ackBlockChangesUpTo")
	void lodecore$setAckBlockChangesUpTo(int sequence);

	@Accessor("nextChatIndex")
	int lodecore$nextChatIndex();

	@Accessor("nextChatIndex")
	void lodecore$setNextChatIndex(int index);

	@Accessor("chatSession")
	@Nullable RemoteChatSession lodecore$chatSession();

	@Accessor("chatSession")
	void lodecore$setChatSession(@Nullable RemoteChatSession session);

	@Accessor("signedMessageDecoder")
	void lodecore$setSignedMessageDecoder(SignedMessageChain.Decoder decoder);

	@Accessor("lastSeenMessages")
	LastSeenMessagesValidator lodecore$lastSeenMessages();

	@Accessor("messageSignatureCache")
	MessageSignatureCache lodecore$messageSignatureCache();
}
