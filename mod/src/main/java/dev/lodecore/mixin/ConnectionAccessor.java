package dev.lodecore.mixin;

import java.net.SocketAddress;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.Connection;

@Mixin(Connection.class)
public interface ConnectionAccessor {
	@Accessor("address")
	void lodecore$setAddress(SocketAddress address);
}
