package com.chenweikeng.imf.nra.dailyplan;

import static org.junit.jupiter.api.Assertions.*;

import com.chenweikeng.imf.nra.ride.RideName;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DailyPlanRideProgressTest {
  @Test
  void bothVariantsAdvanceEitherTaskWithoutMergingLifetimeCounts() {
    for (RideName ordinary :
        new RideName[] {
          RideName.HAUNTED_MANSION, RideName.GUARDIANS_OF_THE_GALAXY_MISSION_BREAKOUT
        }) {
      RideName seasonal = DailyPlanRideProgress.companion(ordinary);
      Map<RideName, Integer> counts = new EnumMap<>(RideName.class);
      counts.put(ordinary, 700);
      counts.put(seasonal, 162);
      Map<String, Integer> baseline = new HashMap<>();
      DailyPlanRideProgress.captureBaseline(baseline, ordinary, counts::get);
      assertEquals(0, DailyPlanRideProgress.progress(seasonal, baseline, counts::get));
      counts.put(ordinary, 701);
      assertEquals(1, DailyPlanRideProgress.progress(seasonal, baseline, counts::get));
      counts.put(seasonal, 164);
      assertEquals(3, DailyPlanRideProgress.progress(ordinary, baseline, counts::get));
      assertEquals(3, DailyPlanRideProgress.progress(seasonal, baseline, counts::get));
      assertEquals(701, counts.get(ordinary));
      assertEquals(164, counts.get(seasonal));
    }
  }

  @Test
  void addingMissingCompanionBaselineDoesNotAwardHistoricalRidesOrResetPrimaryProgress() {
    Map<String, Integer> baseline = new HashMap<>();
    baseline.put("Haunted Mansion Holiday", 160);
    DailyPlanRideProgress.captureBaseline(
        baseline, RideName.HAUNTED_MANSION_HOLIDAY, r -> r == RideName.HAUNTED_MANSION ? 700 : 162);
    assertEquals(160, baseline.get("Haunted Mansion Holiday"));
    assertEquals(700, baseline.get("Haunted Mansion"));
    assertEquals(
        2,
        DailyPlanRideProgress.progress(
            RideName.HAUNTED_MANSION_HOLIDAY,
            baseline,
            r -> r == RideName.HAUNTED_MANSION ? 700 : 162));
  }

  @Test
  void unrelatedRidesRetainIndependentProgressAndMissingBaselinesDoNotCount() {
    Map<String, Integer> baseline = new HashMap<>();
    baseline.put("Splash Mountain", 10);
    assertEquals(2, DailyPlanRideProgress.progress(RideName.SPLASH_MOUNTAIN, baseline, r -> 12));
    assertEquals(0, DailyPlanRideProgress.progress(RideName.HAUNTED_MANSION, baseline, r -> 700));
    assertNull(DailyPlanRideProgress.companion(RideName.SPLASH_MOUNTAIN));
  }
}
