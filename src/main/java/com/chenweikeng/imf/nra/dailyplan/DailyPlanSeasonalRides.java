package com.chenweikeng.imf.nra.dailyplan;

import com.chenweikeng.imf.nra.ride.RideName;
import com.chenweikeng.imf.nra.ride.SeasonalRideSchedule;
import java.time.LocalDate;
import java.util.function.ToIntFunction;

/** The user's calendar applies to local plans, never to server scoring or Daily Objectives. */
public final class DailyPlanSeasonalRides {
  private DailyPlanSeasonalRides() {}

  /** Preserve family progress while changing the displayed version of unfinished local nodes. */
  static boolean updatePlan(DailyPlan plan, LocalDate date, ToIntFunction<RideName> counts) {
    if (plan == null || plan.layers == null) return false;
    boolean changed = false;
    for (DailyPlanLayer layer : plan.layers) {
      if (layer.completed || layer.nodes == null) continue;
      for (int i = layer.nodes.size() - 1; i >= 0; i--) {
        DailyPlanNode node = layer.nodes.get(i);
        if (node == null || node.completed) continue;
        RideName before = RideName.fromMatchString(node.ride);
        if (before == RideName.UNKNOWN) continue;
        if (layer.baselineCounts != null) {
          int oldSize = layer.baselineCounts.size();
          DailyPlanRideProgress.captureBaseline(layer.baselineCounts, before, counts);
          changed |= oldSize != layer.baselineCounts.size();
        }
        if (layer.fromDailyQuest) continue;
        RideName after = SeasonalRideSchedule.forDate(before, date);
        if (after == before) continue;
        String target = after.toMatchString();
        boolean alreadyPresent =
            layer.nodes.stream().anyMatch(other -> other != node && target.equals(other.ride));
        if (alreadyPresent) {
          layer.nodes.remove(i);
        } else {
          node.ride = target;
        }
        changed = true;
      }
    }
    return changed;
  }
}
