package com.chenweikeng.imf.nra.strategy;

import com.chenweikeng.imf.nra.config.ClosestRideMode;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.config.SortingRules;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.chenweikeng.imf.nra.ride.RideName;
import com.chenweikeng.imf.nra.util.TimeFormatUtil;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * Shared types, helpers, and formatting logic for strategy HUD renderers (V0, V1, V2).
 *
 * <p>Extracted from the three renderer versions to eliminate the ~250 lines of byte-for-byte
 * duplicate code that had accumulated across V0 and V1. Each renderer keeps only its own layout,
 * positioning, and animation logic; everything else delegates here.
 */
public final class StrategyHudBase {

  private StrategyHudBase() {}

  // ---- shared types ----

  public record LayoutInput(
      Minecraft client,
      List<RideGoal> goals,
      RideName currentRide,
      RideName autograbRide,
      RideName closestRide,
      RideName effectiveRide,
      boolean currentRideInTop,
      boolean isPassenger,
      String error,
      int availableWidth,
      int gap) {}

  public record LayoutDecision(boolean useShortNames, boolean twoColumns, boolean visible) {}

  public enum RideStatus {
    NORMAL,
    RIDING,
    AUTOGRABBING,
    CLOSEST
  }

  public static class FormattedRide {
    private final String name;
    private final RideStatus status;

    public FormattedRide(String name, RideStatus status) {
      this.name = name;
      this.status = status;
    }

    public String getName() {
      return name;
    }

    public RideStatus getStatus() {
      return status;
    }
  }

  // ---- shared helpers ----

  /** Filters a closest-ride candidate through the user's {@link ClosestRideMode} config. */
  public static RideName filterClosestRide(RideName ride) {
    if (ride == null) {
      return null;
    }
    ClosestRideMode mode = ModConfig.currentSetting.closestRideMode;
    if (mode == ClosestRideMode.NEVER) {
      return null;
    }
    if (mode == ClosestRideMode.ONLY_IN_PROGRESS) {
      RideGoal goal = StrategyCalculator.getGoalForRide(ride);
      if (goal == null) {
        return null;
      }
    }
    return ride;
  }

  /** Animated-dot sequence for "Autograbbing…" labels. */
  public static String getAnimatedDots() {
    long currentTimeMillis = System.currentTimeMillis();
    int quarterSecond = (int) ((currentTimeMillis % 2000) / 500);
    return switch (quarterSecond) {
      case 0 -> "";
      case 1 -> ".";
      case 2 -> "..";
      case 3 -> "...";
      default -> "";
    };
  }

  /**
   * Formats a ride name with status annotations (progress/remaining time for riding rides,
   * "Autograbbing…" for autograb targets, "Closest" for closest-ride candidates).
   */
  public static FormattedRide formatRideName(
      RideName ride,
      RideName currentRide,
      RideName autograbRide,
      RideName closestRide,
      boolean useShortNames,
      boolean isPassenger) {
    String rideName = useShortNames ? ride.getShortName() : ride.getDisplayName();
    RideStatus status = RideStatus.NORMAL;

    if (currentRide != null && ride == currentRide) {
      Integer elapsed = CurrentRideHolder.getElapsedSeconds();
      Integer progress = CurrentRideHolder.getCurrentProgressPercent();
      if (elapsed != null && progress != null) {
        int totalSeconds = ride.getRideTime();
        int remainingSeconds = totalSeconds - elapsed;
        if (remainingSeconds < 0) {
          remainingSeconds = 0;
        }
        String timeLeft = TimeFormatUtil.formatDuration(remainingSeconds);
        rideName += " (" + progress + "%, " + timeLeft + " left)";
      } else if (progress != null) {
        rideName += " (" + progress + "%)";
      }
      status = RideStatus.RIDING;
    } else if (currentRide == null
        && autograbRide != null
        && ride == autograbRide
        && !isPassenger) {
      rideName += " (Autograbbing" + getAnimatedDots() + ")";
      status = RideStatus.AUTOGRABBING;
    } else if (currentRide == null
        && autograbRide == null
        && closestRide != null
        && ride == closestRide) {
      rideName += " (Closest)";
      status = RideStatus.CLOSEST;
    }

    return new FormattedRide(rideName, status);
  }

  /**
   * Formats a goal line: "NAME - N more, <duration>".
   *
   * <p>When {@code status == CLOSEST && ridesNeeded == 0} the suffix is omitted (the ride is
   * already at its goal — just show the name).
   */
  public static String formatGoalText(
      FormattedRide formattedRide, RideGoal goal, SortingRules sortingRules) {
    int ridesNeeded;
    long timeNeeded;
    if (sortingRules == SortingRules.TOTAL_TIME_ASC
        || sortingRules == SortingRules.TOTAL_TIME_DESC) {
      ridesNeeded = goal.getMaxRidesNeeded();
      timeNeeded = goal.getMaxTimeNeeded();
    } else {
      ridesNeeded = goal.getNextGoalRidesNeeded();
      timeNeeded = goal.getNextGoalTimeNeeded();
    }
    if (formattedRide.getStatus() == RideStatus.CLOSEST && ridesNeeded == 0) {
      return formattedRide.getName();
    }
    return String.format(
        "%s - %d more, %s",
        formattedRide.getName(), ridesNeeded, TimeFormatUtil.formatDuration(timeNeeded));
  }

  /** Maps a {@link RideStatus} to the configured ARGB colour. */
  public static int getColorForStatus(
      RideStatus status,
      int colorNormal,
      int colorRiding,
      int colorAutograbbing,
      int colorClosest) {
    return switch (status) {
      case RIDING -> colorRiding;
      case AUTOGRABBING -> colorAutograbbing;
      case CLOSEST -> colorClosest;
      case NORMAL -> colorNormal;
    };
  }

  /** Maximum pixel width of the formatted-goal text across a list of {@link RideGoal}s. */
  public static int computeMaxWidth(
      LayoutInput layoutInput, List<RideGoal> goals, boolean useShortNames) {
    int max = 0;
    for (RideGoal goal : goals) {
      FormattedRide formattedRide =
          formatRideName(
              goal.getRide(),
              layoutInput.currentRide,
              layoutInput.autograbRide,
              layoutInput.closestRide,
              useShortNames,
              layoutInput.isPassenger);
      String text = formatGoalText(formattedRide, goal, ModConfig.currentSetting.sortingRules);
      max = Math.max(max, layoutInput.client.font.width(text));
    }
    return max;
  }
}
