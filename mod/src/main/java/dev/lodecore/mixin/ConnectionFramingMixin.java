package dev.lodecore.mixin;

import dev.lodecore.net.PrefixCompressionEncoder;
import dev.lodecore.net.PrefixFrameEncoder;
import io.netty.channel.ChannelOutboundHandler;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Connection.class)
abstract class ConnectionFramingMixin {
	@Inject(method = "createFrameEncoder", at = @At("HEAD"), cancellable = true)
	private static void lodecore$reuseFrameBuffer(boolean local, CallbackInfoReturnable<ChannelOutboundHandler> ci) {
		if (!local) ci.setReturnValue(new PrefixFrameEncoder());
	}

	@Redirect(method = "setupCompression", at = @At(value = "NEW", target = "net/minecraft/network/CompressionEncoder"))
	private CompressionEncoder lodecore$reuseSmallPackets(int threshold) {
		return new PrefixCompressionEncoder(threshold);
	}
}
