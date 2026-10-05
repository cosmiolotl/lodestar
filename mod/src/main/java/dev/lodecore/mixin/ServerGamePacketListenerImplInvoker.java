package dev.lodecore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.server.network.ServerGamePacketListenerImpl;

@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerImplInvoker {
	@Invoker("markClientLoaded")
	void lodecore$markClientLoaded();
}
