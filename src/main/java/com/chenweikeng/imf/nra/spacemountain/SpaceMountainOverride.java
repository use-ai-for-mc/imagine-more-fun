package com.chenweikeng.imf.nra.spacemountain;

import com.chenweikeng.imf.nra.ServerState;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Gates client-side overrides that should only fire while the player is actually riding Space
 * Mountain or Hyperspace Mountain on ImagineFun. Reads are intentionally lock-free; a one-tick
 * stale value just delays the override flipping by one frame and is harmless.
 */
public final class SpaceMountainOverride {
  private static final boolean SMOOTH_COASTERS_AVAILABLE =
      FabricLoader.getInstance().isModLoaded("smoothcoasters");

  private SpaceMountainOverride() {}

  public static boolean isActive() {
    return SpaceMountainActivationPolicy.shouldActivate(
        SMOOTH_COASTERS_AVAILABLE,
        ModConfig.currentSetting.spaceMountainEnhancements,
        ServerState.isImagineFunServer(),
        CurrentRideHolder.getCurrentRide());
  }
}
