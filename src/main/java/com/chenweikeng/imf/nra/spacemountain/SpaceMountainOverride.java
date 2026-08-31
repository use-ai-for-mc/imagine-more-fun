package com.chenweikeng.imf.nra.spacemountain;

import com.chenweikeng.imf.nra.ServerState;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.chenweikeng.imf.nra.ride.RideName;

/**
 * Gates client-side overrides that should only fire while the player is actually riding Space
 * Mountain or Hyperspace Mountain on ImagineFun. Reads are intentionally lock-free; a one-tick
 * stale value just delays the override flipping by one frame and is harmless.
 */
public final class SpaceMountainOverride {
  private SpaceMountainOverride() {}

  public static boolean isActive() {
    // Master config toggle (Modifications tab → Space Mountain group). Every Space Mountain
    // feature routes through isActive(), so this one check gates all of them.
    if (!ModConfig.currentSetting.spaceMountainEnhancements) return false;
    if (!ServerState.isImagineFunServer()) return false;
    RideName ride = CurrentRideHolder.getCurrentRide();
    return ride == RideName.SPACE_MOUNTAIN || ride == RideName.HYPERSPACE_MOUNTAIN;
  }
}
