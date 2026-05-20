package com.chenweikeng.imf.nra.config;

/**
 * Per-ride override for the maximum ride goal. {@link #USE_SYSTEM} defers to {@link
 * ConfigSetting#maxGoal} (capped at 10K); the higher tiers let an individual ride exceed that cap.
 */
public enum RideMaxGoalOverride {
  USE_SYSTEM(-1),
  K20(20000),
  K50(50000),
  K100(100000);

  private final int value;

  RideMaxGoalOverride(int value) {
    this.value = value;
  }

  /** Returns the override value, or -1 for {@link #USE_SYSTEM}. */
  public int getValue() {
    return value;
  }

  public String getDisplayName() {
    return switch (this) {
      case USE_SYSTEM -> "Use system";
      case K20 -> "20K";
      case K50 -> "50K";
      case K100 -> "100K";
    };
  }
}
