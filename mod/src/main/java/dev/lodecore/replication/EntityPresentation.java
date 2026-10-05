package dev.lodecore.replication;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import static dev.lodecore.replication.EntityWire.*;

final class EntityPresentation {
	static void applyMove(ServerLevel level, Entity mirror, Move move) {
		if (!mirror.isPassenger()) {
			mirror.setPos(move.position());
		}

		mirror.setYRot(move.yRot());
		mirror.setXRot(move.xRot());
		mirror.setYHeadRot(move.headRot());
		boolean motionChanged = !mirror.getDeltaMovement().equals(move.motion());
		mirror.setDeltaMovement(move.motion());
		mirror.setOnGround(move.onGround());
		// Periodic trackers retain vanilla's cadence. Types without a periodic update
		// still need an explicit push when their authority moves them.
		if (!mirror.getType().hasUpdateInterval() || motionChanged && !mirror.getType().trackDeltas()) {
			mirror.needsSync = true;
		}

		if (mirror instanceof ServerPlayer player) {
			level.getChunkSource().move(player);
		}

		// Riders are moved by their vehicle's tick, which a mirror does not have.
		for (Entity passenger : mirror.getPassengers()) {
			mirror.positionRider(passenger);
		}
	}

	static void applyData(Entity mirror, List<SynchedEntityData.DataValue<?>> values) {
		ReplicatedData data = (ReplicatedData) mirror.getEntityData();

		for (SynchedEntityData.DataValue<?> value : values) {
			data.lodecore$assign(value);
		}
	}

	static void applyEquipment(ServerLevel level, LivingEntity mirror, List<Pair<EquipmentSlot, ItemStack>> equipment) {
		if (equipment.isEmpty()) {
			return;
		}

		List<Pair<EquipmentSlot, ItemStack>> changed = new ArrayList<>();
		for (Pair<EquipmentSlot, ItemStack> slot : equipment) {
			if (ItemStack.matches(mirror.getItemBySlot(slot.getFirst()), slot.getSecond())) continue;
			mirror.setItemSlot(slot.getFirst(), slot.getSecond());
			changed.add(slot);
		}

		// The game sends equipment changes from the entity's tick, which a mirror does not have.
		if (!changed.isEmpty()) {
			level.getChunkSource().sendToTrackingPlayers(mirror, new ClientboundSetEquipmentPacket(mirror.getId(), changed));
		}
	}

	static void show(ServerLevel level, Entity mirror, Effect effect) {
		switch (effect) {
			case Swing(InteractionHand hand, SwingAnimation animation) ->
					level.getChunkSource().sendToTrackingPlayers(mirror, new ClientboundSwingAnimationPacket(mirror, hand, animation));
			case Event(byte id) -> level.broadcastEntityEvent(mirror, id);
			case Animate(int action) -> level.getChunkSource().sendToTrackingPlayers(mirror, new ClientboundAnimatePacket(mirror, action));
			case Damage(DamageSource source) -> level.broadcastDamageEvent(mirror, source);
		}
	}
}
