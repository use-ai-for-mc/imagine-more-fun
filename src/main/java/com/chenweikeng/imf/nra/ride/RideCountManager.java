package com.chenweikeng.imf.nra.ride;

import com.chenweikeng.imf.ImfFileIO;
import com.chenweikeng.imf.ImfStorage;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RideCountManager {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final File DATA_FILE = ImfStorage.nraRides().toFile();

  private final Map<RideName, Integer> rideCounts = new ConcurrentHashMap<>();
  private final Map<RideName, Integer> lastSavedCounts = new HashMap<>();
  private long lastSaveTime = 0;
  private static final long SAVE_INTERVAL_MS = 15000; // 15 seconds

  private static RideCountManager instance;

  private RideCountManager() {
    load();
  }

  public static RideCountManager getInstance() {
    if (instance == null) {
      instance = new RideCountManager();
    }
    return instance;
  }

  /**
   * Updates the ride count for a given ride. Returns true if the count increased (indicating a
   * change that should trigger a save).
   */
  public boolean updateRideCount(RideName ride, int count) {
    if (ride == RideName.UNKNOWN) {
      return false; // Don't track unknown rides
    }

    Integer previousCount = rideCounts.get(ride);
    boolean increased = previousCount == null || count > previousCount;

    rideCounts.put(ride, count);

    return increased;
  }

  /** Imports a ride count, only updating if the imported value is higher than the current. */
  public void importRideCount(RideName ride, int count) {
    if (ride == RideName.UNKNOWN) return;
    if (count > getRideCount(ride)) {
      rideCounts.put(ride, count);
    }
  }

  /** Gets the current count for a ride, or 0 if not set. */
  public int getRideCount(RideName ride) {
    return rideCounts.getOrDefault(ride, 0);
  }

  public boolean hasRideCount(RideName ride) {
    return rideCounts.containsKey(ride);
  }

  /** Gets all ride counts. */
  public Map<RideName, Integer> getAllRideCounts() {
    return new HashMap<>(rideCounts);
  }

  /**
   * Checks if there are any changes (increases) and saves if needed. Should be called periodically
   * (every tick or every few seconds).
   */
  public void checkAndSaveIfNeeded() {
    long currentTime = System.currentTimeMillis();

    // Check if 15 seconds have passed since last save
    if (currentTime - lastSaveTime < SAVE_INTERVAL_MS) {
      return;
    }

    // Check if there are any increases compared to last saved state
    boolean hasIncreases = false;
    for (Map.Entry<RideName, Integer> entry : rideCounts.entrySet()) {
      RideName ride = entry.getKey();
      Integer currentCount = entry.getValue();
      Integer lastSavedCount = lastSavedCounts.get(ride);

      if (lastSavedCount == null || currentCount > lastSavedCount) {
        hasIncreases = true;
        break;
      }
    }

    if (hasIncreases) {
      save();
      lastSaveTime = currentTime;
      // Update last saved counts
      lastSavedCounts.clear();
      lastSavedCounts.putAll(rideCounts);
    }
  }

  /** Loads ride counts from file. */
  private void load() {
    if (DATA_FILE.exists()) {
      Type type = new TypeToken<Map<String, Integer>>() {}.getType();
      Map<String, Integer> stringMap =
          ImfFileIO.readJson(
              DATA_FILE.toPath(), GSON, type, NotRidingAlertClient.LOGGER, "ride counts");

      if (stringMap != null) {
        for (Map.Entry<String, Integer> entry : stringMap.entrySet()) {
          RideName ride = RideName.fromMatchString(entry.getKey());
          if (ride != RideName.UNKNOWN) {
            rideCounts.put(ride, entry.getValue());
            lastSavedCounts.put(ride, entry.getValue());
          }
        }
      }
    }
  }

  /** Saves ride counts to file. */
  private void save() {
    // Convert enum keys to strings for JSON serialization
    Map<String, Integer> stringMap = new HashMap<>();
    for (Map.Entry<RideName, Integer> entry : rideCounts.entrySet()) {
      stringMap.put(entry.getKey().toMatchString(), entry.getValue());
    }
    ImfFileIO.writeJsonAtomic(
        DATA_FILE.toPath(), GSON, stringMap, NotRidingAlertClient.LOGGER, "ride counts");
  }

  /** Force save immediately (useful for testing or shutdown). */
  public void forceSave() {
    save();
    lastSaveTime = System.currentTimeMillis();
    lastSavedCounts.clear();
    lastSavedCounts.putAll(rideCounts);
  }

  public boolean hasBasicCounts() {
    if (!hasRideCount(RideName.ALICE_IN_WONDERLAND)) {
      return false;
    }
    if (!hasRideCount(RideName.MATTERHORN_BOBSLEDS)) {
      return false;
    }
    if (!hasRideCount(RideName.GOLDEN_ZEPHYR)) {
      return false;
    }
    if (!hasRideCount(RideName.FLIKS_FLYERS)) {
      return false;
    }

    return true;
  }
}
