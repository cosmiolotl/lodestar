package dev.lodecore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.server.level.ServerEntity;

@Mixin(ServerEntity.class)
public interface ServerEntityAccessor {
	@Accessor("positionCodec")
	VecDeltaCodec lodecore$positionCodec();
}
