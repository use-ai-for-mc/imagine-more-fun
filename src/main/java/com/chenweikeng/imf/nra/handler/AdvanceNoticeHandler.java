package com.chenweikeng.imf.nra.handler;

import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.config.WindowMinimizeTiming;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.chenweikeng.imf.nra.ride.RideName;
import com.chenweikeng.imf.nra.util.SoundHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

public class AdvanceNoticeHandler {
  private static final Identifier RIDE_COMPLETE_SOUND =
      Identifier.fromNamespaceAndPath("minecraft", "ride.complete");

  private boolean alreadyNotified = false;
  private RideName lastTrackedRide = null;

  public void tick(Minecraft client) {
    RideName currentRide = CurrentRideHolder.getCurrentRide();

    if (currentRide != lastTrackedRide) {
      alreadyNotified = false;
      lastTrackedRide = currentRide;
    }

    if (currentRide == null || alreadyNotified) {
      return;
    }

    int advanceSeconds = ModConfig.currentSetting.getAdvanceNoticeSeconds(currentRide);
    if (advanceSeconds <= 0) {
      return;
    }

    Integer remaining = CurrentRideHolder.remainingSeconds();
    if (remaining == null) {
      return;
    }

    if (remaining <= advanceSeconds) {
      alreadyNotified = true;
      playRideCompleteSound(client);
      SoundHelper.playConfiguredSound(client);

      if (ModConfig.currentSetting.minimizeWindow != WindowMinimizeTiming.NONE) {
        WindowMinimizeHandler.getInstance().restoreWindow();
      }
    }
  }

  private void playRideCompleteSound(Minecraft client) {
    if (client.player == null || client.level == null) {
      return;
    }
    client.level.playSound(
        client.player,
        client.player.getX(),
        client.player.getY(),
        client.player.getZ(),
        SoundEvent.createVariableRangeEvent(RIDE_COMPLETE_SOUND),
        SoundSource.MASTER,
        1.0f,
        1.0f);
  }

  public void reset() {
    alreadyNotified = false;
    lastTrackedRide = null;
  }
}
