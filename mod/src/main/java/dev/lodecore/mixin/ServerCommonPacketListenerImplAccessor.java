package dev.lodecore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;

@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerCommonPacketListenerImplAccessor {
	@Accessor("connection")
	Connection lodecore$connection();

	/** A player moving between nodes keeps their listener, and with it everything that knows them by it. */
	@Mutable
	@Accessor("connection")
	void lodecore$setConnection(Connection connection);

	@Accessor("keepAliveTime")
	long lodecore$keepAliveTime();

	@Accessor("keepAliveTime")
	void lodecore$setKeepAliveTime(long time);

	@Accessor("keepAlivePending")
	boolean lodecore$keepAlivePending();

	@Accessor("keepAlivePending")
	void lodecore$setKeepAlivePending(boolean pending);

	@Accessor("keepAliveChallenge")
	long lodecore$keepAliveChallenge();

	@Accessor("keepAliveChallenge")
	void lodecore$setKeepAliveChallenge(long challenge);

	@Accessor("latency")
	void lodecore$setLatency(int latency);
}
