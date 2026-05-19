package com.chenweikeng.imf.nra.handler;

import com.chenweikeng.imf.nra.GameState;
import com.chenweikeng.imf.nra.ServerState;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.spacemountain.SpaceMountainOverride;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

public class DayTimeHandler {
  private static final long NOON = 6000L;
  private static final long SUNSET_START = 12000L;

  public void resetDayTimeIfNeeded(Minecraft client) {
    if (!ServerState.isImagineFunServer()) {
      return;
    }

    if (FireworkViewingHandler.getInstance().isViewingFirework()) {
      return;
    }

    ClientLevel level = client.level;
    if (level == null) {
      return;
    }

    boolean isRiding = GameState.getInstance().isRiding();
    // Fullbright is forced off while the Space Mountain dark-ride override is active, so the dome
    // stays dark for the star effect — regardless of the configured fullbright mode.
    boolean shouldApplyFullbright =
        ModConfig.currentSetting.fullbrightMode.shouldApply(isRiding)
            && !SpaceMountainOverride.isActive();

    if (!shouldApplyFullbright) {
      return;
    }

    long time = level.getDayTime() % 24000L;

    if (time >= SUNSET_START) {
      level.getLevelData().setDayTime(NOON);
    }
  }
}
