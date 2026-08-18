package com.chenweikeng.imf.nra;

import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.handler.FireworkViewingHandler;
import com.chenweikeng.imf.nra.handler.SystemAttentionHandler;
import com.chenweikeng.imf.nra.tracker.PlayerMovementTracker;
import com.chenweikeng.imf.nra.tracker.RideStateTracker;
import com.chenweikeng.imf.nra.tracker.SuppressionRegionTracker;
import com.chenweikeng.imf.nra.util.SoundHelper;
import net.minecraft.client.Minecraft;

public class AlertChecker {

  public void check(
      Minecraft client,
      boolean autograbFailureActive,
      PlayerMovementTracker movementTracker,
      RideStateTracker rideStateTracker,
      SuppressionRegionTracker suppressionRegionTracker) {
    if (client.player == null) {
      return;
    }

    if (autograbFailureActive) {
      SoundHelper.playConfiguredSound(client);
      SystemAttentionHandler.getInstance().onAlert(client);
      return;
    }

    if (!ModConfig.currentSetting.enabled) {
      return;
    }

    GameState state = GameState.getInstance();
    if (!state.isRiding()
        && !movementTracker.hasPlayerMovedRecently(state.getAbsoluteTickCounter())
        && !rideStateTracker.hasRidenRecently(state.getAbsoluteTickCounter())
        && !rideStateTracker.hasVehicleRecently(state.getAbsoluteTickCounter())
        && !suppressionRegionTracker.isInROTRExceptionArea(client)
        && !rideStateTracker.isLincolnSuppressionActive()
        && !FireworkViewingHandler.getInstance().isViewingFirework()) {
      SoundHelper.playConfiguredSound(client);
      SystemAttentionHandler.getInstance().onAlert(client);
    }
  }
}
