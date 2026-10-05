package dev.lodecore.mixin;

import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;

import com.mojang.authlib.GameProfile;
import dev.lodecore.forwarding.ForwardedLogin;
import dev.lodecore.forwarding.Forwarding;
import dev.lodecore.handoff.HandoffLogin;
import dev.lodecore.replication.Hooks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

@Mixin(ServerLoginPacketListenerImpl.class)
abstract class ServerLoginPacketListenerImplMixin implements ForwardedLogin, HandoffLogin {
	@Shadow
	private GameProfile authenticatedProfile;

	@Shadow
	@Final
	private Connection connection;

	@Unique
	private final CompletableFuture<Void> lodecore$answered = new CompletableFuture<>();

	@Unique
	private volatile boolean lodecore$verified;

	@Unique
	private final CompletableFuture<Void> lodecore$handoffAnswered = new CompletableFuture<>();

	@Unique
	private volatile boolean lodecore$handingOff;

	@Unique
	private volatile long lodecore$handoffToken;

	/** Set once the connection waits here for its player to arrive from another node. */
	@Unique
	private volatile boolean lodecore$parked;

	@Shadow
	public abstract void disconnect(Component reason);

	@Override
	public CompletableFuture<Void> lodecore$answered() {
		return lodecore$answered;
	}

	@Override
	public void lodecore$accept(GameProfile profile, SocketAddress address) {
		this.authenticatedProfile = profile;
		((ConnectionAccessor) this.connection).lodecore$setAddress(address);
		// Written last: it publishes the two writes above to the game thread.
		this.lodecore$verified = true;
	}

	@Override
	public CompletableFuture<Void> lodecore$handoffAnswered() {
		return lodecore$handoffAnswered;
	}

	@Override
	public void lodecore$setHandoffToken(long token) {
		this.lodecore$handoffToken = token;
		this.lodecore$handingOff = true;
	}

	/** Fails closed: nobody gets past login on the identity they claimed for themselves. */
	@Inject(method = "verifyLoginAndFinishConnectionSetup", at = @At("HEAD"), cancellable = true)
	private void lodecore$requireForwardedIdentity(GameProfile profile, CallbackInfo ci) {
		if (!this.lodecore$verified) {
			this.disconnect(Forwarding.NOT_THROUGH_PROXY);
			ci.cancel();
		} else if (this.lodecore$handingOff && !Hooks.expectsHandoff(this.authenticatedProfile.id(), this.lodecore$handoffToken)) {
			this.disconnect(Component.literal("This server is not expecting you."));
			ci.cancel();
		}
	}

	/** The caller read the profile before the proxy's answer replaced it. */
	@ModifyVariable(method = "verifyLoginAndFinishConnectionSetup", at = @At("HEAD"), argsOnly = true)
	private GameProfile lodecore$useForwardedIdentity(GameProfile claimed) {
		return this.lodecore$verified ? this.authenticatedProfile : claimed;
	}

	/** A player on their way from another node is logged in, and waits here until they arrive. */
	@Inject(method = "finishLoginAndWaitForClient", at = @At("TAIL"))
	private void lodecore$park(GameProfile profile, CallbackInfo ci) {
		if (!this.lodecore$handingOff) {
			return;
		}

		if (Hooks.parkHandoffLogin(this.authenticatedProfile, this.lodecore$handoffToken, this.connection)) {
			this.lodecore$parked = true;
		} else {
			this.disconnect(Component.literal("This server is not expecting you."));
		}
	}

	/**
	 * The proxy ends a waiting connection's login as the client would, which readies it for the
	 * protocol to switch; but it switches to play, when the player arrives, not to configuration.
	 */
	@Inject(method = "handleLoginAcknowledgement", at = @At("HEAD"), cancellable = true)
	private void lodecore$stayParked(CallbackInfo ci) {
		if (this.lodecore$parked) {
			ci.cancel();
		}
	}

	/** A waiting connection is not timed out as a slow login; the move it waits for is. */
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void lodecore$waitWhileParked(CallbackInfo ci) {
		if (this.lodecore$parked) {
			ci.cancel();
		}
	}
}
