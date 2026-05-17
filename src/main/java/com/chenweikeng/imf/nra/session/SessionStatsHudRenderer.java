package com.chenweikeng.imf.nra.session;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.ServerState;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.chenweikeng.imf.nra.ride.RideName;
import com.chenweikeng.imf.nra.util.TimeFormatUtil;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public class SessionStatsHudRenderer {
  private static int debugThrottle = 0;
  private static final int PADDING = 4;
  private static final int LINE_HEIGHT = 10;
  private static final int TEXT_COLOR = 0xFFFFFFFF;

  public static void render(GuiGraphics context, DeltaTracker tickCounter) {
    if (!ServerState.isImagineFunServer()) {
      return;
    }
    if (!ModConfig.currentSetting.globalEnable) {
      return;
    }
    if (!ModConfig.currentSetting.showSessionStats) {
      return;
    }

    SessionTracker tracker = SessionTracker.getInstance();
    if (!tracker.isActive()) {
      return;
    }

    Minecraft client = Minecraft.getInstance();
    if (client.player == null) {
      return;
    }

    int screenWidth = client.getWindow().getGuiScaledWidth();
    int screenHeight = client.getWindow().getGuiScaledHeight();

    int rides = tracker.getRidesToday();
    long rideTime = tracker.getRideTimeToday();
    int streak = tracker.getCurrentStreak();

    RideName currentRide = CurrentRideHolder.getCurrentRide();
    Integer elapsed = CurrentRideHolder.getElapsedSeconds();
    if (currentRide != null && elapsed != null && elapsed > 0) {
      rideTime += elapsed;
    }

    long onlineSeconds = tracker.getOnlineSeconds();
    double onlineHours = onlineSeconds / 3600.0;
    int rideTimePerHourMin = 0;
    if (onlineHours > 0) {
      long rideTimePerHourSec = Math.round(rideTime / onlineHours);
      rideTimePerHourMin = (int) (rideTimePerHourSec / 60);
    }

    if (rideTimePerHourMin > 60 && debugThrottle++ % 200 == 0) {
      NotRidingAlertClient.LOGGER.debug(
          "[SessionDebug] m/hr EXCEEDED 60: {}m/hr | rideTime={}s (completed={}s + currentElapsed={}s) | onlineSeconds={}s | onlineHours={} | currentRide={} | rides={}",
          rideTimePerHourMin,
          rideTime,
          tracker.getRideTimeToday(),
          (elapsed != null ? elapsed : 0),
          onlineSeconds,
          String.format("%.4f", onlineHours),
          (currentRide != null ? currentRide.name() : "none"),
          rides);
    }

    String timeStr = TimeFormatUtil.formatDuration(rideTime);

    String statsLine =
        String.format("Today: %d rides | %s | %dm/hr", rides, timeStr, rideTimePerHourMin);

    int statsWidth = client.font.width(statsLine);
    int totalWidth = statsWidth;

    String streakLine = null;
    int streakWidth = 0;
    if (streak > 0) {
      streakLine = String.format("Streak: %d day%s", streak, streak == 1 ? "" : "s");
      streakWidth = client.font.width(streakLine);
      totalWidth = Math.max(totalWidth, streakWidth);
    }

    int lineCount = streak > 0 ? 2 : 1;
    int boxWidth = totalWidth + PADDING * 2;
    int boxHeight = lineCount * LINE_HEIGHT + PADDING * 2;

    int boxX = screenWidth - boxWidth - PADDING;
    int boxY = screenHeight - boxHeight - PADDING;

    int opacity = ModConfig.currentSetting.hudBackgroundOpacity;
    if (opacity > 0) {
      int alpha = (int) (opacity * 2.55);
      int bgColor = alpha << 24;
      context.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, bgColor);
    }

    int textX = boxX + PADDING;
    int textY = boxY + PADDING;

    context.drawString(client.font, statsLine, textX, textY, TEXT_COLOR, true);

    if (streakLine != null) {
      context.drawString(client.font, streakLine, textX, textY + LINE_HEIGHT, TEXT_COLOR, true);
    }
  }
}
