package dev.lodecore.handoff;

import dev.lodecore.mixin.LastSeenMessagesValidatorAccessor;
import dev.lodecore.mixin.MessageSignatureCacheAccessor;
import dev.lodecore.mixin.ServerGamePacketListenerImplAccessor;
import dev.lodecore.mixin.SignedMessageChainAccessor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.ObjectList;
import java.time.Instant;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.LastSeenTrackedEntry;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.chat.SignedMessageChain;
import net.minecraft.network.chat.SignedMessageLink;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.SignatureValidator;
import net.minecraft.world.entity.player.ProfilePublicKey;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HandoffChat {
	private static final Logger LOGGER = LoggerFactory.getLogger("lodecore/handoff");
	static void captureChat(CompoundTag tag, ServerGamePacketListenerImpl listener, ServerGamePacketListenerImplAccessor game) {
		tag.putInt("ChatIndex", game.lodecore$nextChatIndex());
		RemoteChatSession session = game.lodecore$chatSession();

		if (session != null) {
			ByteBuf out = Unpooled.buffer();
			RemoteChatSession.Data.STREAM_CODEC.encode(out, session.asData());
			tag.putByteArray("ChatSession", ByteBufUtil.getBytes(out));
			SignedMessageChain chain = ((ChatChainHolder) listener).lodecore$chain();

			if (chain != null) {
				SignedMessageChainAccessor links = (SignedMessageChainAccessor) chain;
				SignedMessageLink next = links.lodecore$nextLink();

				if (next == null) {
					tag.putBoolean("ChainBroken", true);
				} else {
					tag.putInt("ChainIndex", next.index());
				}

				tag.putLong("ChainTime", links.lodecore$lastTimeStamp().toEpochMilli());
			}
		}

		LastSeenMessagesValidatorAccessor lastSeen = (LastSeenMessagesValidatorAccessor) game.lodecore$lastSeenMessages();
		ListTag tracked = new ListTag();

		synchronized (game.lodecore$lastSeenMessages()) {
			for (LastSeenTrackedEntry entry : lastSeen.lodecore$trackedMessages()) {
				CompoundTag seen = new CompoundTag();

				if (entry != null) {
					seen.putByteArray("Signature", entry.signature().bytes());
					seen.putBoolean("Pending", entry.pending());
				}

				tracked.add(seen);
			}

			MessageSignature lastPending = lastSeen.lodecore$lastPendingMessage();

			if (lastPending != null) {
				tag.putByteArray("LastPending", lastPending.bytes());
			}
		}

		tag.put("LastSeen", tracked);
		ListTag cache = new ListTag();

		for (MessageSignature signature : ((MessageSignatureCacheAccessor) game.lodecore$messageSignatureCache()).lodecore$entries()) {
			CompoundTag entry = new CompoundTag();

			if (signature != null) {
				entry.putByteArray("Signature", signature.bytes());
			}

			cache.add(entry);
		}

		tag.put("SignatureCache", cache);
	}
	static void restoreChat(MinecraftServer server, ServerPlayer player, CompoundTag tag, ServerGamePacketListenerImplAccessor game) {
		game.lodecore$setNextChatIndex(tag.getIntOr("ChatIndex", 0));
		Optional<byte[]> sessionBytes = tag.getByteArray("ChatSession");
		SignatureValidator validator = server.services().profileKeySignatureValidator();

		if (sessionBytes.isPresent() && validator != null) {
			try {
				RemoteChatSession.Data data = RemoteChatSession.Data.STREAM_CODEC.decode(Unpooled.wrappedBuffer(sessionBytes.get()));
				RemoteChatSession session = data.validate(player.getGameProfile(), validator);
				SignedMessageChain chain = new SignedMessageChain(player.getUUID(), session.sessionId());
				SignedMessageChainAccessor links = (SignedMessageChainAccessor) chain;
				links.lodecore$setNextLink(tag.getBooleanOr("ChainBroken", false)
						? null
						: new SignedMessageLink(tag.getIntOr("ChainIndex", 0), player.getUUID(), session.sessionId()));
				links.lodecore$setLastTimeStamp(Instant.ofEpochMilli(tag.getLongOr("ChainTime", 0)));
				((ChatChainHolder) player.connection).lodecore$setChain(chain);
				game.lodecore$setChatSession(session);
				game.lodecore$setSignedMessageDecoder(chain.decoder(session.profilePublicKey()));
				player.setChatSession(session);
			} catch (ProfilePublicKey.ValidationException | RuntimeException e) {
				LOGGER.warn("Could not take over the chat session of {}: {}", player.getPlainTextName(), e.toString());
			}
		}

		LastSeenMessagesValidatorAccessor lastSeen = (LastSeenMessagesValidatorAccessor) game.lodecore$lastSeenMessages();

		synchronized (game.lodecore$lastSeenMessages()) {
			ObjectList<@Nullable LastSeenTrackedEntry> tracked = lastSeen.lodecore$trackedMessages();
			tracked.clear();

			for (Tag entry : tag.getListOrEmpty("LastSeen")) {
				CompoundTag seen = (CompoundTag) entry;
				tracked.add(seen.getByteArray("Signature")
						.map(signature -> new LastSeenTrackedEntry(new MessageSignature(signature), seen.getBooleanOr("Pending", false)))
						.orElse(null));
			}

			lastSeen.lodecore$setLastPendingMessage(tag.getByteArray("LastPending").map(MessageSignature::new).orElse(null));
		}

		MessageSignature[] cache = ((MessageSignatureCacheAccessor) game.lodecore$messageSignatureCache()).lodecore$entries();
		ListTag entries = tag.getListOrEmpty("SignatureCache");

		for (int i = 0; i < cache.length; i++) {
			cache[i] = i < entries.size() ? ((CompoundTag) entries.get(i)).getByteArray("Signature").map(MessageSignature::new).orElse(null) : null;
		}
	}
}
