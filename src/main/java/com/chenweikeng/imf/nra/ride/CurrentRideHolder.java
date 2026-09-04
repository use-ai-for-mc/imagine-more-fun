package com.chenweikeng.imf.nra.ride;

import com.chenweikeng.imf.nra.handler.ClosedCaptionHolder;

/**
 * Holds the currently ridden ride from the scoreboard sidebar. Null when the "Current Ride" block
 * is not shown (player not riding).
 */
public class CurrentRideHolder {
  private static RideName currentRide = null;
  private static Integer currentProgressPercent = null;
  private static Integer elapsedSeconds = null;

  public static RideName getCurrentRide() {
    return currentRide;
  }

  public static void setCurrentRide(RideName ride) {
    boolean isNewRide = currentRide == null && ride != null;
    boolean rideChanged = ride != currentRide;
    currentRide = ride;
    if (ride == null) {
      currentProgressPercent = null;
      elapsedSeconds = null;
    }
    if (isNewRide) {
      ClosedCaptionHolder.getInstance().randomizeColorSeed();
    }
    if (rideChanged) {
      ClosedCaptionHolder.getInstance().onRideChanged(ride);
    }
  }

  public static Integer getCurrentProgressPercent() {
    return currentProgressPercent;
  }

  public static void setCurrentProgressPercent(Integer percent) {
    currentProgressPercent = percent;
  }

  public static Integer getElapsedSeconds() {
    return elapsedSeconds;
  }

  public static void setElapsedSeconds(Integer seconds) {
    elapsedSeconds = seconds;
  }

  public static Integer remainingSeconds() {
    return remainingSeconds(currentRide, elapsedSeconds);
  }

  public static Integer remainingSeconds(RideName ride, Integer elapsed) {
    if (ride == null || elapsed == null) {
      return null;
    }
    int rideTime = ride.getRideTime();
    if (rideTime <= 0) {
      return null;
    }
    return Math.max(0, rideTime - elapsed);
  }
}
