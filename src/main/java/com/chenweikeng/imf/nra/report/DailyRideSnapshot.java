package com.chenweikeng.imf.nra.report;

import com.chenweikeng.imf.ImfFileIO;
import com.chenweikeng.imf.ImfStorage;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.ride.RideCountManager;
import com.chenweikeng.imf.nra.ride.RideName;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class DailyRideSnapshot {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final File DATA_FILE = ImfStorage.nraRideSnapshots().toFile();
  private static final int MAX_DAYS = 90;
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

  private static DailyRideSnapshot instance;

  private final Map<String, SnapshotEntry> snapshots = new TreeMap<>();

  public static class SnapshotEntry {
    public Map<String, Integer> rideCounts = new HashMap<>();
    public int ridesCompleted;
    public long totalRideTimeSeconds;
    public long totalOnlineSeconds;
    public int streak;

    // Pin activity stats
    public int pinHoarderTrades;
    public int pinBoxesOpened;
    public int newMintPinsAdded;

    // Food consumption stats
    public Map<String, Integer> foodConsumed = new HashMap<>();
  }

  private DailyRideSnapshot() {
    load();
  }

  public static DailyRideSnapshot getInstance() {
    if (instance == null) {
      instance = new DailyRideSnapshot();
    }
    return instance;
  }

  public void snapshotDay(
      String date, int ridesCompleted, long totalRideTimeSeconds, long totalOnlineSeconds) {
    snapshotDay(
        date, ridesCompleted, totalRideTimeSeconds, totalOnlineSeconds, 0, 0, 0, new HashMap<>());
  }

  public void snapshotDay(
      String date,
      int ridesCompleted,
      long totalRideTimeSeconds,
      long totalOnlineSeconds,
      int pinHoarderTrades,
      int pinBoxesOpened,
      int newMintPinsAdded) {
    snapshotDay(
        date,
        ridesCompleted,
        totalRideTimeSeconds,
        totalOnlineSeconds,
        pinHoarderTrades,
        pinBoxesOpened,
        newMintPinsAdded,
        new HashMap<>());
  }

  public void snapshotDay(
      String date,
      int ridesCompleted,
      long totalRideTimeSeconds,
      long totalOnlineSeconds,
      int pinHoarderTrades,
      int pinBoxesOpened,
      int newMintPinsAdded,
      Map<String, Integer> foodConsumed) {
    if (ridesCompleted <= 0
        && pinHoarderTrades <= 0
        && pinBoxesOpened <= 0
        && newMintPinsAdded <= 0
        && (foodConsumed == null || foodConsumed.isEmpty())) {
      return;
    }

    SnapshotEntry entry = new SnapshotEntry();
    entry.ridesCompleted = ridesCompleted;
    entry.totalRideTimeSeconds = totalRideTimeSeconds;
    entry.totalOnlineSeconds = totalOnlineSeconds;
    entry.pinHoarderTrades = pinHoarderTrades;
    entry.pinBoxesOpened = pinBoxesOpened;
    entry.newMintPinsAdded = newMintPinsAdded;
    if (foodConsumed != null) {
      entry.foodConsumed = new HashMap<>(foodConsumed);
    }

    RideCountManager countManager = RideCountManager.getInstance();
    Map<RideName, Integer> allCounts = countManager.getAllRideCounts();
    for (Map.Entry<RideName, Integer> rideEntry : allCounts.entrySet()) {
      if (rideEntry.getKey() != RideName.UNKNOWN) {
        entry.rideCounts.put(rideEntry.getKey().toMatchString(), rideEntry.getValue());
      }
    }

    snapshots.put(date, entry);
    pruneOld();
    save();
  }

  public SnapshotEntry getSnapshot(String date) {
    return snapshots.get(date);
  }

  public List<String> getAvailableDates() {
    List<String> dates = new ArrayList<>(snapshots.keySet());
    dates.sort((a, b) -> b.compareTo(a));
    return dates;
  }

  public boolean hasSnapshot(String date) {
    return snapshots.containsKey(date);
  }

  /** Returns a defensive copy of all snapshots for export. */
  public Map<String, SnapshotEntry> getAllSnapshots() {
    return new TreeMap<>(snapshots);
  }

  /**
   * Merges imported snapshots. For each date, keeps the entry with the higher ridesCompleted count.
   */
  public void mergeSnapshots(Map<String, SnapshotEntry> imported) {
    if (imported == null) return;
    for (Map.Entry<String, SnapshotEntry> entry : imported.entrySet()) {
      String date = entry.getKey();
      SnapshotEntry importedEntry = entry.getValue();
      SnapshotEntry existing = snapshots.get(date);
      if (existing == null || importedEntry.ridesCompleted > existing.ridesCompleted) {
        snapshots.put(date, importedEntry);
      }
    }
    pruneOld();
    save();
  }

  private void pruneOld() {
    if (snapshots.size() <= MAX_DAYS) {
      return;
    }
    List<String> sortedDates = new ArrayList<>(snapshots.keySet());
    sortedDates.sort(String::compareTo);
    while (snapshots.size() > MAX_DAYS) {
      snapshots.remove(sortedDates.remove(0));
    }
  }

  private void load() {
    if (DATA_FILE.exists()) {
      Type type = new TypeToken<Map<String, SnapshotEntry>>() {}.getType();
      Map<String, SnapshotEntry> loaded =
          ImfFileIO.readJson(
              DATA_FILE.toPath(), GSON, type, NotRidingAlertClient.LOGGER, "ride snapshots");
      if (loaded != null) {
        snapshots.putAll(loaded);
      }
    }
  }

  private void save() {
    ImfFileIO.writeJsonAtomic(
        DATA_FILE.toPath(), GSON, snapshots, NotRidingAlertClient.LOGGER, "ride snapshots");
  }

  public String getMostRecentDate() {
    List<String> dates = getAvailableDates();
    return dates.isEmpty() ? null : dates.get(0);
  }

  /**
   * Returns the most recent snapshot date strictly before the given date, skipping any gap days
   * with no data. Returns null if no prior snapshot exists within MAX_DAYS.
   */
  public String getMostRecentDateBefore(String date) {
    try {
      LocalDate d = LocalDate.parse(date, DATE_FORMAT);
      for (int i = 1; i <= MAX_DAYS; i++) {
        String candidate = d.minusDays(i).format(DATE_FORMAT);
        if (snapshots.containsKey(candidate)) {
          return candidate;
        }
      }
      return null;
    } catch (Exception e) {
      return null;
    }
  }

  /** Returns the nearest older snapshot date, skipping gap days. */
  public String getPreviousDate(String date) {
    return getMostRecentDateBefore(date);
  }

  /** Returns the nearest newer snapshot date, skipping gap days. */
  public String getNextDate(String date) {
    try {
      LocalDate d = LocalDate.parse(date, DATE_FORMAT);
      for (int i = 1; i <= MAX_DAYS; i++) {
        String candidate = d.plusDays(i).format(DATE_FORMAT);
        if (snapshots.containsKey(candidate)) {
          return candidate;
        }
      }
      return null;
    } catch (Exception e) {
      return null;
    }
  }
}
