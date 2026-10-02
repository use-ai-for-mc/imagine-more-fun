package com.chenweikeng.imf.nra.dailyplan;

import com.chenweikeng.imf.nra.ride.RideName;
import java.util.Map;
import java.util.function.ToIntFunction;

/** Local plan tasks accept either member of the two seasonal ride families. */
public final class DailyPlanRideProgress {
  private DailyPlanRideProgress() {}

  public static RideName companion(RideName ride) {
    return switch (ride) {
      case HAUNTED_MANSION -> RideName.HAUNTED_MANSION_HOLIDAY;
      case HAUNTED_MANSION_HOLIDAY -> RideName.HAUNTED_MANSION;
      case GUARDIANS_OF_THE_GALAXY_MISSION_BREAKOUT ->
          RideName.GUARDIANS_OF_THE_GALAXY_MONSTERS_AFTER_DARK;
      case GUARDIANS_OF_THE_GALAXY_MONSTERS_AFTER_DARK ->
          RideName.GUARDIANS_OF_THE_GALAXY_MISSION_BREAKOUT;
      default -> null;
    };
  }

  public static void captureBaseline(
      Map<String, Integer> baseline, RideName ride, ToIntFunction<RideName> counts) {
    baseline.putIfAbsent(ride.toMatchString(), counts.applyAsInt(ride));
    RideName other = companion(ride);
    if (other != null) baseline.putIfAbsent(other.toMatchString(), counts.applyAsInt(other));
  }

  public static int progress(
      RideName ride, Map<String, Integer> baseline, ToIntFunction<RideName> counts) {
    if (baseline == null) return 0;
    long delta = delta(ride, baseline, counts);
    RideName other = companion(ride);
    if (other != null) delta += delta(other, baseline, counts);
    return (int) Math.min(Integer.MAX_VALUE, delta);
  }

  private static long delta(
      RideName ride, Map<String, Integer> baseline, ToIntFunction<RideName> counts) {
    Integer start = baseline.get(ride.toMatchString());
    return start == null ? 0 : Math.max(0L, (long) counts.applyAsInt(ride) - start);
  }
}
