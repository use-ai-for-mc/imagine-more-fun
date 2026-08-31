package com.chenweikeng.imf.nra.spacemountain;

import com.chenweikeng.imf.nra.ride.RideName;

/** Pure activation policy shared by the runtime gate and its tests. */
final class SpaceMountainActivationPolicy {
  private SpaceMountainActivationPolicy() {}

  static boolean shouldActivate(
      boolean smoothCoastersAvailable,
      boolean enhancementsEnabled,
      boolean imagineFunServer,
      RideName ride) {
    if (!smoothCoastersAvailable || !enhancementsEnabled || !imagineFunServer) {
      return false;
    }
    return ride == RideName.SPACE_MOUNTAIN || ride == RideName.HYPERSPACE_MOUNTAIN;
  }
}
