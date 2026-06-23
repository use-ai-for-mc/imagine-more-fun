package com.chenweikeng.imf.nra.session;

import com.chenweikeng.imf.ImfFileIO;
import com.chenweikeng.imf.ImfStorage;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.report.DailyRideSnapshot;
import com.chenweikeng.imf.nra.report.RideReportNotifier;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class DailySessionData {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final File DATA_FILE = ImfStorage.nraSession().toFile();
  private static final long SAVE_INTERVAL_MS = 15000;
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

  public String date;
  public int ridesCompleted;
  public long totalRideTimeSeconds;
  public long totalOnlineSeconds;
  public int currentStreak;
  public String lastActiveDate;

  // Pin activity tracking
  public int pinHoarderTrades;
  public int pinBoxesOpened;
  public int newMintPinsAdded;

  // Food consumption tracking
  public Map<String, Integer> foodConsumed = new HashMap<>();

  private transient boolean dirty = false;
  private transient long lastSaveTime = 0;
  private transient long currentSessionStartMs = 0;

  public DailySessionData() {
    this.date = today();
    this.ridesCompleted = 0;
    this.totalRideTimeSeconds = 0;
    this.totalOnlineSeconds = 0;
    this.currentStreak = 0;
    this.lastActiveDate = null;
    this.pinHoarderTrades = 0;
    this.pinBoxesOpened = 0;
    this.newMintPinsAdded = 0;
  }

  public void startSession() {
    currentSessionStartMs = System.currentTimeMillis();
  }

  public void endSession() {
    if (currentSessionStartMs > 0) {
      flushSessionTime();
      currentSessionStartMs = 0;
      dirty = true;
    }
  }

  public long getOnlineSeconds() {
    long live = 0;
    if (currentSessionStartMs > 0) {
      live = (System.currentTimeMillis() - currentSessionStartMs) / 1000;
    }
    return totalOnlineSeconds + live;
  }

  public void checkDateRollover() {
    String todayStr = today();
    if (todayStr.equals(date)) {
      return;
    }

    boolean wasInSession = currentSessionStartMs > 0;
    if (wasInSession) {
      endSession();
    }

    String previousDate = date;

    // Snapshot the day's ride counts before resetting
    if (ridesCompleted > 0
        || pinHoarderTrades > 0
        || pinBoxesOpened > 0
        || newMintPinsAdded > 0
        || !foodConsumed.isEmpty()) {
      DailyRideSnapshot.getInstance()
          .snapshotDay(
              previousDate,
              ridesCompleted,
              totalRideTimeSeconds,
              totalOnlineSeconds,
              pinHoarderTrades,
              pinBoxesOpened,
              newMintPinsAdded,
              foodConsumed);
      RideReportNotifier.getInstance().onDateRollover(previousDate);
    }

    updateStreak(todayStr);
    date = todayStr;
    ridesCompleted = 0;
    totalRideTimeSeconds = 0;
    totalOnlineSeconds = 0;
    pinHoarderTrades = 0;
    pinBoxesOpened = 0;
    newMintPinsAdded = 0;
    foodConsumed.clear();
    dirty = true;

    if (wasInSession) {
      startSession();
    }
  }

  private void updateStreak(String todayStr) {
    if (lastActiveDate == null) {
      return;
    }
    try {
      LocalDate last = LocalDate.parse(lastActiveDate, DATE_FORMAT);
      LocalDate today = LocalDate.parse(todayStr, DATE_FORMAT);
      long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(last, today);
      if (daysBetween == 1) {
        currentStreak++;
      } else if (daysBetween > 1) {
        currentStreak = 0;
      }
    } catch (Exception e) {
      currentStreak = 0;
    }
  }

  public void onRideCompleted(long rideTimeSeconds) {
    ridesCompleted++;
    totalRideTimeSeconds += rideTimeSeconds;

    String todayStr = today();
    if (lastActiveDate == null || !lastActiveDate.equals(todayStr)) {
      if (currentStreak == 0) {
        currentStreak = 1;
      }
      lastActiveDate = todayStr;
    }
    dirty = true;
  }

  public void onPinHoarderTrade() {
    pinHoarderTrades++;
    dirty = true;
  }

  public void onPinBoxOpened() {
    pinBoxesOpened++;
    dirty = true;
  }

  public void onNewMintPinAdded() {
    newMintPinsAdded++;
    dirty = true;
  }

  public void onFoodConsumed(String itemName) {
    foodConsumed.put(itemName, foodConsumed.getOrDefault(itemName, 0) + 1);
    dirty = true;
  }

  public Map<String, Integer> getFoodConsumed() {
    return new HashMap<>(foodConsumed);
  }

  public void markDirty() {
    dirty = true;
  }

  public void saveIfDirty() {
    long now = System.currentTimeMillis();
    if (now - lastSaveTime < SAVE_INTERVAL_MS) {
      return;
    }
    // Always save periodically while in a session to keep online time accurate on disk
    if (!dirty && currentSessionStartMs <= 0) {
      return;
    }
    save();
  }

  public void forceSave() {
    save();
  }

  private void save() {
    flushSessionTime();
    if (ImfFileIO.writeJsonAtomic(
        DATA_FILE.toPath(), GSON, this, NotRidingAlertClient.LOGGER, "session data")) {
      dirty = false;
      lastSaveTime = System.currentTimeMillis();
    }
  }

  /** Moves the current session's live time into totalOnlineSeconds so it gets persisted. */
  private void flushSessionTime() {
    if (currentSessionStartMs > 0) {
      long now = System.currentTimeMillis();
      totalOnlineSeconds += (now - currentSessionStartMs) / 1000;
      currentSessionStartMs = now;
    }
  }

  public static DailySessionData load() {
    if (DATA_FILE.exists()) {
      DailySessionData data =
          ImfFileIO.readJson(
              DATA_FILE.toPath(),
              GSON,
              DailySessionData.class,
              NotRidingAlertClient.LOGGER,
              "session data");
      if (data != null) {
        return data;
      }
    }
    return new DailySessionData();
  }

  private static String today() {
    return LocalDate.now().format(DATE_FORMAT);
  }
}
