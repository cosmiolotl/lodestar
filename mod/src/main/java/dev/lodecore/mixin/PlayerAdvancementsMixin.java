package dev.lodecore.mixin;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.lodecore.storage.SavedAdvancements;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerAdvancements.class)
abstract class PlayerAdvancementsMixin implements SavedAdvancements {
	@Shadow @Final private Map<AdvancementHolder, AdvancementProgress> progress;
	@Unique private String lodecore$savedAdvancements;

	@Unique private String lodecore$incomingProgress;

	@Override
	public void lodecore$restoreAdvancements(ServerAdvancementManager manager, String json) {
		lodecore$incomingProgress = json.isEmpty() ? "{}" : json;
		try { ((PlayerAdvancements) (Object) this).reload(manager); }
		finally { lodecore$incomingProgress = null; }
	}

	@Redirect(method = "load", at = @At(value = "INVOKE",
			target = "Ljava/nio/file/Files;isRegularFile(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z"))
	private boolean lodecore$hasProgress(Path path, LinkOption[] options) {
		return lodecore$incomingProgress != null || Files.isRegularFile(path, options);
	}

	@Redirect(method = "load", at = @At(value = "INVOKE",
			target = "Ljava/nio/file/Files;newBufferedReader(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/io/BufferedReader;"))
	private BufferedReader lodecore$readProgress(Path path, Charset charset) throws IOException {
		if (lodecore$incomingProgress == null) return Files.newBufferedReader(path, charset);
		return new BufferedReader(new StringReader(lodecore$incomingProgress));
	}

	@Override
	public String lodecore$savedAdvancements() {
		if (lodecore$savedAdvancements != null) return lodecore$savedAdvancements;
		JsonObject snapshot = new JsonObject();
		progress.forEach((holder, value) -> {
			if (value.hasProgress()) {
				snapshot.add(holder.id().toString(), AdvancementProgress.CODEC.encodeStart(JsonOps.INSTANCE, value).getOrThrow());
			}
		});
		snapshot.addProperty("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
		lodecore$savedAdvancements = snapshot.toString();
		return lodecore$savedAdvancements;
	}

	// The client progressChanged set is cleared by packet delivery independently of saves.
	@Inject(method = {"award", "revoke"}, at = @At("RETURN"))
	private void lodecore$progressChanged(CallbackInfoReturnable<Boolean> ci) {
		if (ci.getReturnValueZ()) lodecore$savedAdvancements = null;
	}

	@Inject(method = {"reload", "startProgress"}, at = @At("HEAD"))
	private void lodecore$progressReplaced(CallbackInfo ci) {
		lodecore$savedAdvancements = null;
	}
}
