package com.chenweikeng.imf.nra.handler;

import com.chenweikeng.imf.nra.compat.MonkeycraftCompat;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.config.SortingRules;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.chenweikeng.imf.nra.ride.RideName;
import com.chenweikeng.imf.nra.strategy.RideGoal;
import com.chenweikeng.imf.nra.strategy.StrategyCalculator;
import com.chenweikeng.imf.nra.util.TimeFormatUtil;
import net.minecraft.client.Minecraft;

public class HibernationHandler {
  private static final int CANCELLATION_DELAY_TICKS = 60;
  private static final int MESSAGE_UPDATE_INTERVAL_TICKS = 20;
  private static final long NOTIFICATION_RESCHEDULE_THRESHOLD_MS = 2000;
  private static HibernationHandler instance;

  private RideName previousCurrentRide = null;
  private long rideEndTick = -1;
  private boolean pendingCancellation = false;
  private boolean hibernationActive = false;
  private boolean wasHibernationEligibleRide = false;

  private int currentRideTimeSeconds = -1;
  private long lastMessageUpdateTick = -1;
  private long scheduledFireAtEpochMs = -1;
  private boolean previousHibernationSetting = true;

  private HibernationHandler() {}

  public static HibernationHandler getInstance() {
    if (instance == null) {
      instance = new HibernationHandler();
    }
    return instance;
  }

  public void track(Minecraft client, long currentTick) {
    if (!MonkeycraftCompat.isAvailable()) {
      return;
    }

    boolean currentSetting = ModConfig.currentSetting.hibernationWhenRiding;

    if (previousHibernationSetting && !currentSetting && hibernationActive) {
      MonkeycraftCompat.endHibernation();
      hibernationActive = false;
    }

    RideName currentRide = CurrentRideHolder.getCurrentRide();

    if (currentRide != null && previousCurrentRide == null) {
      onRideStart(currentRide, currentSetting);
    } else if (currentRide == null && previousCurrentRide != null) {
      onRideEnd(currentTick);
    } else if (currentRide != null && currentRide != previousCurrentRide) {
      onRideEnd(currentTick);
      onRideStart(currentRide, currentSetting);
    }

    if (!previousHibernationSetting
        && currentSetting
        && currentRide != null
        && !hibernationActive
        && wasHibernationEligibleRide) {
      Integer progressPercent = CurrentRideHolder.getCurrentProgressPercent();
      int initialProgress = progressPercent != null ? progressPercent : 0;
      MonkeycraftCompat.startHibernation(buildHibernationMessage(currentRide, initialProgress));
      hibernationActive = true;
      lastMessageUpdateTick = currentTick;
    }

    if (pendingCancellation && currentTick - rideEndTick >= CANCELLATION_DELAY_TICKS) {
      executeCancellation();
    }

    if (currentRide != null && wasHibernationEligibleRide) {
      maybeRescheduleCompletionNotification(currentRide);
    }

    if (hibernationActive && currentRideTimeSeconds > 0) {
      if (currentTick - lastMessageUpdateTick >= MESSAGE_UPDATE_INTERVAL_TICKS) {
        updateHibernationMessage();
        lastMessageUpdateTick = currentTick;
      }
    }

    previousCurrentRide = currentRide;
    previousHibernationSetting = currentSetting;
  }

  private void onRideStart(RideName ride, boolean hibernationEnabled) {
    if (ride == RideName.DAVY_CROCKETTS_EXPLORER_CANOES || ride == RideName.UNKNOWN) {
      wasHibernationEligibleRide = false;
      return;
    }

    pendingCancellation = false;
    wasHibernationEligibleRide = true;

    currentRideTimeSeconds = ride.getRideTime();
    lastMessageUpdateTick = -1;
    scheduledFireAtEpochMs = -1;

    Integer progressPercent = CurrentRideHolder.getCurrentProgressPercent();
    int initialProgress = progressPercent != null ? progressPercent : 0;

    if (hibernationEnabled) {
      MonkeycraftCompat.startHibernation(buildHibernationMessage(ride, initialProgress));
      hibernationActive = true;
    }

    maybeRescheduleCompletionNotification(ride);
  }

  private void onRideEnd(long currentTick) {
    if (hibernationActive) {
      MonkeycraftCompat.endHibernation();
      hibernationActive = false;
    }

    currentRideTimeSeconds = -1;
    lastMessageUpdateTick = -1;
    scheduledFireAtEpochMs = -1;

    if (wasHibernationEligibleRide) {
      rideEndTick = currentTick;
      pendingCancellation = true;
    }
  }

  private void updateHibernationMessage() {
    RideName currentRide = CurrentRideHolder.getCurrentRide();
    if (currentRide == null || currentRideTimeSeconds <= 0) {
      return;
    }

    Integer progressPercent = CurrentRideHolder.getCurrentProgressPercent();
    int progress = progressPercent != null ? progressPercent : 0;
    MonkeycraftCompat.setHibernationMessage(buildHibernationMessage(currentRide, progress));
  }

  private String buildHibernationMessage(RideName ride, int progressPercent) {
    StringBuilder sb = new StringBuilder();
    sb.append("Riding ").append(ride.getDisplayName());
    sb.append(" (").append(progressPercent).append("%)");

    Integer remainingSeconds = CurrentRideHolder.remainingSeconds();
    if (remainingSeconds != null) {
      sb.append("\n");
      sb.append(TimeFormatUtil.formatDuration(remainingSeconds)).append(" left");
    }

    return sb.toString();
  }

  private void maybeRescheduleCompletionNotification(RideName ride) {
    Integer remainingSeconds = CurrentRideHolder.remainingSeconds();
    if (remainingSeconds == null || ride.getRideTime() >= 99999) {
      return;
    }

    long fireAtEpochMs = System.currentTimeMillis() + remainingSeconds * 1000L;
    if (scheduledFireAtEpochMs >= 0
        && Math.abs(fireAtEpochMs - scheduledFireAtEpochMs)
            <= NOTIFICATION_RESCHEDULE_THRESHOLD_MS) {
      return;
    }

    MonkeycraftCompat.setTimedNotification(
        fireAtEpochMs, "Ride finished", buildNotificationBody(ride), true, ride.getDisplayName());
    scheduledFireAtEpochMs = fireAtEpochMs;
  }

  private String buildNotificationBody(RideName ride) {
    RideGoal goal = StrategyCalculator.getGoalForRide(ride);
    String rideName = ride.getDisplayName();

    if (ModConfig.currentSetting.sortingRules == SortingRules.TOTAL_TIME_ASC
        || ModConfig.currentSetting.sortingRules == SortingRules.TOTAL_TIME_DESC) {
      if (goal == null || goal.getMaxRidesNeeded() <= 1) {
        return rideName + " has finished";
      }

      int ridesNeeded = goal.getMaxRidesNeeded();
      return rideName + " has finished (needs " + (ridesNeeded - 1) + " more rides)";
    } else {
      if (goal == null || goal.getNextGoalRidesNeeded() <= 1) {
        return rideName + " has finished";
      }

      int ridesNeeded = goal.getNextGoalRidesNeeded();
      return rideName + " has finished (needs " + (ridesNeeded - 1) + " more rides)";
    }
  }

  public void cancelPendingCancellation() {
    pendingCancellation = false;
  }

  private void executeCancellation() {
    MonkeycraftCompat.cancelTimedNotification();
    pendingCancellation = false;
  }

  public void reset() {
    if (hibernationActive) {
      MonkeycraftCompat.endHibernation();
    }
    previousCurrentRide = null;
    rideEndTick = -1;
    pendingCancellation = false;
    hibernationActive = false;
    wasHibernationEligibleRide = false;
    currentRideTimeSeconds = -1;
    lastMessageUpdateTick = -1;
    scheduledFireAtEpochMs = -1;
  }
}
