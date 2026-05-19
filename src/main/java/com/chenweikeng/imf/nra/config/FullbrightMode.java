package com.chenweikeng.imf.nra.config;

public enum FullbrightMode {
  NONE,
  ONLY_WHEN_RIDING,
  ONLY_WHEN_NOT_RIDING,
  ALWAYS;

  /** Whether this mode calls for fullbright, given whether the player is currently riding. */
  public boolean shouldApply(boolean isRiding) {
    return switch (this) {
      case NONE -> false;
      case ONLY_WHEN_RIDING -> isRiding;
      case ONLY_WHEN_NOT_RIDING -> !isRiding;
      case ALWAYS -> true;
    };
  }
}
