package com.chenweikeng.imf.nra.ride;

import java.time.LocalDate;
import java.util.Set;

/** User-specified calendar for recommendations; actual ride identity comes from the server. */
public final class SeasonalRideSchedule {
  private SeasonalRideSchedule() {}

  public static RideName forDate(RideName ride, LocalDate date) {
    if (ride == RideName.GUARDIANS_OF_THE_GALAXY_MISSION_BREAKOUT
        || ride == RideName.GUARDIANS_OF_THE_GALAXY_MONSTERS_AFTER_DARK) {
      return date.getMonthValue() == 10
          ? RideName.GUARDIANS_OF_THE_GALAXY_MONSTERS_AFTER_DARK
          : RideName.GUARDIANS_OF_THE_GALAXY_MISSION_BREAKOUT;
    }
    if (ride == RideName.HAUNTED_MANSION || ride == RideName.HAUNTED_MANSION_HOLIDAY) {
      boolean holiday =
          date.getMonthValue() >= 10 || (date.getMonthValue() == 1 && date.getDayOfMonth() <= 15);
      return holiday ? RideName.HAUNTED_MANSION_HOLIDAY : RideName.HAUNTED_MANSION;
    }
    return ride;
  }

  /** Either enabled version keeps a facility in the plan; hiding both excludes it. */
  public static boolean isHidden(RideName ride, Set<String> hidden) {
    if (hidden == null) return false;
    if (ride == RideName.HAUNTED_MANSION || ride == RideName.HAUNTED_MANSION_HOLIDAY) {
      return hidden.contains(RideName.HAUNTED_MANSION.toMatchString())
          && hidden.contains(RideName.HAUNTED_MANSION_HOLIDAY.toMatchString());
    }
    if (ride == RideName.GUARDIANS_OF_THE_GALAXY_MISSION_BREAKOUT
        || ride == RideName.GUARDIANS_OF_THE_GALAXY_MONSTERS_AFTER_DARK) {
      return hidden.contains(RideName.GUARDIANS_OF_THE_GALAXY_MISSION_BREAKOUT.toMatchString())
          && hidden.contains(RideName.GUARDIANS_OF_THE_GALAXY_MONSTERS_AFTER_DARK.toMatchString());
    }
    return hidden.contains(ride.toMatchString());
  }
}
