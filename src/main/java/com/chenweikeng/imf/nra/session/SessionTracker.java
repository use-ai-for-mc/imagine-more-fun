package com.chenweikeng.imf.nra.session;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.ride.RideName;
import java.util.HashMap;
import java.util.Map;

public class SessionTracker {
  private static SessionTracker instance;

  private DailySessionData data;

  private SessionTracker() {}

  public static SessionTracker getInstance() {
    if (instance == null) {
      instance = new SessionTracker();
    }
    return instance;
  }

  public void onSessionStart() {
    data = DailySessionData.load();
    data.checkDateRollover();
    data.startSession();
    data.forceSave();
  }

  public void onRideCompleted(RideName ride) {
    if (data == null) {
      return;
    }
    data.checkDateRollover();
    int rideTime = ride.getRideTime();
    long onlineBefore = data.getOnlineSeconds();
    long rideTimeBefore = data.totalRideTimeSeconds;
    data.onRideCompleted(rideTime);
    NotRidingAlertClient.LOGGER.debug(
        "[SessionDebug] rideCompleted: ride={} rideTime={}s onlineSeconds={} rideTimeBefore={}s rideTimeAfter={}s",
        ride.name(),
        rideTime,
        onlineBefore,
        rideTimeBefore,
        data.totalRideTimeSeconds);
    MilestoneHandler.checkMilestone(data.ridesCompleted, data.totalRideTimeSeconds);
  }

  public int getRidesToday() {
    return data != null ? data.ridesCompleted : 0;
  }

  public long getRideTimeToday() {
    return data != null ? data.totalRideTimeSeconds : 0;
  }

  public long getOnlineSeconds() {
    return data != null ? data.getOnlineSeconds() : 0;
  }

  public int getCurrentStreak() {
    return data != null ? data.currentStreak : 0;
  }

  public int getPinHoarderTrades() {
    return data != null ? data.pinHoarderTrades : 0;
  }

  public int getPinBoxesOpened() {
    return data != null ? data.pinBoxesOpened : 0;
  }

  public int getNewMintPinsAdded() {
    return data != null ? data.newMintPinsAdded : 0;
  }

  public void onPinHoarderTrade() {
    if (data != null) {
      data.checkDateRollover();
      data.onPinHoarderTrade();
    }
  }

  public void onPinBoxOpened() {
    if (data != null) {
      data.checkDateRollover();
      data.onPinBoxOpened();
    }
  }

  public void onNewMintPinAdded() {
    if (data != null) {
      data.checkDateRollover();
      data.onNewMintPinAdded();
    }
  }

  public void onFoodConsumed(String itemName) {
    if (data != null) {
      data.checkDateRollover();
      data.onFoodConsumed(itemName);
    }
  }

  public Map<String, Integer> getFoodConsumed() {
    return data != null ? data.getFoodConsumed() : new HashMap<>();
  }

  public void checkAndSaveIfNeeded() {
    if (data != null) {
      data.checkDateRollover();
      data.saveIfDirty();
    }
  }

  public void onSessionEnd() {
    if (data != null) {
      data.endSession();
      data.forceSave();
    }
  }

  public boolean isActive() {
    return data != null;
  }

  /**
   * Bumps today's rides counter up to the value implied by a just-imported data bundle, using max
   * semantics so existing in-progress work is never lost. Works whether a session is currently
   * active (updates in-memory and force-saves) or not (loads from disk, updates, saves). Returns
   * true if any field was actually changed.
   *
   * <p>Intentionally does NOT touch {@code totalRideTimeSeconds}. Imported rides were performed in
   * another client at another time, so counting them against today's online time would push the
   * "ride time per online hour" HUD metric above the 60 m/hr ceiling. Ride time stays a measure of
   * actual live activity in this client.
   */
  public boolean realignTodayFromImport(int impliedRidesToday) {
    DailySessionData target = (data != null) ? data : DailySessionData.load();
    target.checkDateRollover();
    if (impliedRidesToday <= target.ridesCompleted) {
      return false;
    }
    target.ridesCompleted = impliedRidesToday;
    target.markDirty();
    target.forceSave();
    return true;
  }
}
