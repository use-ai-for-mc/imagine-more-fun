package com.chenweikeng.imf.nra.strategy;

import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.config.SortingRules;
import com.chenweikeng.imf.nra.ride.AutograbHolder;
import com.chenweikeng.imf.nra.ride.RideCountManager;
import com.chenweikeng.imf.nra.ride.RideName;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class StrategyCalculator {
  private static final List<Integer> BASE_GOALS = List.of(1, 10, 100, 500);

  /**
   * Calculates the top N easiest goals (goals that take the least time to reach).
   *
   * @param topN The number of top goals to return
   * @return List of RideGoal objects sorted by time needed (easiest first)
   */
  public static List<RideGoal> getTopGoals(int topN) {
    List<RideGoal> allGoals = new ArrayList<>();
    RideCountManager countManager = RideCountManager.getInstance();

    Integer minRideTimeMinutes = ModConfig.currentSetting.minRideTimeMinutes;
    boolean onlyAutograbbing = ModConfig.currentSetting.onlyAutograbbing;

    // Calculate goals for each ride
    for (RideName ride : RideName.sortedByDisplayName()) {

      // Skip non-autograbbing rides if onlyAutograbbing is enabled
      if (onlyAutograbbing && !AutograbHolder.hasAutograb(ride)) {
        continue;
      }

      // Skip hidden rides
      if (ModConfig.currentSetting.hiddenRides.contains(ride.toMatchString())) {
        continue;
      }

      int currentCount = countManager.getRideCount(ride);

      int maxGoal = ModConfig.currentSetting.getMaxGoalForRide(ride);

      if (currentCount >= maxGoal) {
        continue;
      }

      // Find the next goal
      int nextGoal = findNextGoal(currentCount, maxGoal);

      if (nextGoal == -1) {
        continue; // No more goals
      }

      // Calculate rides needed and time
      int nextRidesNeeded = nextGoal - currentCount;
      int maxRidesNeeded = maxGoal - currentCount;
      int rideTimeSeconds = ride.getRideTime();

      // Skip if ride time is invalid (99999 means not provided)
      if (rideTimeSeconds >= 99999) {
        StrategyHudRendererDispatcher.setError(
            "Ride time not provided for: " + ride.getDisplayName());
        continue;
      }

      // Skip if ride time is below minimum filter
      if (minRideTimeMinutes != null) {
        int rideTimeMinutes = rideTimeSeconds / 60;
        if (rideTimeMinutes < minRideTimeMinutes) {
          continue;
        }
      }

      long nextTimeNeeded = (long) nextRidesNeeded * rideTimeSeconds;
      long maxTimeNeeded = (long) maxRidesNeeded * rideTimeSeconds;

      allGoals.add(
          new RideGoal(
              ride,
              currentCount,
              nextGoal,
              nextRidesNeeded,
              nextTimeNeeded,
              maxGoal,
              maxRidesNeeded,
              maxTimeNeeded));
    }

    // Sort based on sortingRules config
    SortingRules rules = ModConfig.currentSetting.sortingRules;
    if (rules == null) {
      rules = SortingRules.NEXT_GOAL_ASC;
    }

    switch (rules) {
      case TOTAL_TIME_ASC:
        allGoals.sort(Comparator.comparingLong(RideGoal::getMaxTimeNeeded));
        break;
      case TOTAL_TIME_DESC:
        allGoals.sort(Comparator.comparingLong(RideGoal::getMaxTimeNeeded).reversed());
        break;
      case RIDE_TIME_ASC:
        allGoals.sort(Comparator.comparingInt((RideGoal g) -> (int) g.getRide().getRideTime()));
        break;
      case RIDE_TIME_DESC:
        allGoals.sort(
            Comparator.comparingInt((RideGoal g) -> (int) g.getRide().getRideTime()).reversed());
        break;
      case NEXT_GOAL_ASC:
        allGoals.sort(Comparator.comparingLong(RideGoal::getNextGoalTimeNeeded));
        break;
      case NEXT_GOAL_DESC:
        allGoals.sort(Comparator.comparingLong(RideGoal::getNextGoalTimeNeeded).reversed());
        break;
    }

    // Return top N
    int returnCount = Math.min(topN, allGoals.size());
    return allGoals.subList(0, returnCount);
  }

  /**
   * Returns a RideGoal for a single ride, or null if it can't be computed (e.g. UNKNOWN, or ride
   * time not set). Does not apply seasonal/hidden filters (for use when showing the current ride
   * outside the top list).
   */
  public static RideGoal getGoalForRide(RideName ride) {
    if (ride == null || ride == RideName.UNKNOWN) {
      return null;
    }
    RideCountManager countManager = RideCountManager.getInstance();
    int currentCount = countManager.getRideCount(ride);
    int maxGoal = ModConfig.currentSetting.getMaxGoalForRide(ride);
    int nextGoal = findNextGoal(currentCount, maxGoal);

    int rideTimeSeconds = ride.getRideTime();
    if (rideTimeSeconds >= 99999) {
      return null;
    }

    if (nextGoal == -1) {
      if (currentCount >= maxGoal) {
        return new RideGoal(ride, currentCount, currentCount, 0, 0, maxGoal, 0, 0);
      } else {
        int maxRidesNeeded = maxGoal - currentCount;
        long maxTimeNeeded = (long) maxRidesNeeded * rideTimeSeconds;
        return new RideGoal(
            ride, currentCount, currentCount, 0, 0, maxGoal, maxRidesNeeded, maxTimeNeeded);
      }
    }

    int ridesNeeded = nextGoal - currentCount;
    int maxRidesNeeded = maxGoal - currentCount;
    long timeNeededSeconds = (long) ridesNeeded * rideTimeSeconds;
    long maxTimeNeeded = (long) maxRidesNeeded * rideTimeSeconds;
    return new RideGoal(
        ride,
        currentCount,
        nextGoal,
        ridesNeeded,
        timeNeededSeconds,
        maxGoal,
        maxRidesNeeded,
        maxTimeNeeded);
  }

  /**
   * Finds the next goal for a given current count, ramping up through the goal ladder until {@code
   * maxGoal} is reached.
   *
   * @param currentCount The current ride count
   * @param maxGoal The effective max goal for the ride (per-ride override or system value)
   * @return The next goal, or -1 if no more goals (already at max)
   */
  private static int findNextGoal(int currentCount, int maxGoal) {
    for (int goal : BASE_GOALS) {
      if (currentCount < goal) {
        return goal;
      }
    }
    if (currentCount < 1000) return 1000;
    if (maxGoal >= 5000 && currentCount < 5000) return 5000;
    if (maxGoal >= 10000 && currentCount < 10000) return 10000;
    if (maxGoal >= 20000 && currentCount < 20000) return 20000;
    if (maxGoal >= 50000 && currentCount < 50000) return 50000;
    if (maxGoal >= 100000 && currentCount < 100000) return 100000;
    return -1;
  }
}
