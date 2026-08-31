package com.chenweikeng.imf.nra.spacemountain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chenweikeng.imf.nra.ride.RideName;
import org.junit.jupiter.api.Test;

class SpaceMountainActivationPolicyTest {
  @Test
  void requiresSmoothCoastersEvenWhenEveryOtherGateIsOpen() {
    assertFalse(
        SpaceMountainActivationPolicy.shouldActivate(false, true, true, RideName.SPACE_MOUNTAIN));
  }

  @Test
  void activatesForSpaceAndHyperspaceWhenEveryGateIsOpen() {
    assertTrue(
        SpaceMountainActivationPolicy.shouldActivate(true, true, true, RideName.SPACE_MOUNTAIN));
    assertTrue(
        SpaceMountainActivationPolicy.shouldActivate(
            true, true, true, RideName.HYPERSPACE_MOUNTAIN));
  }

  @Test
  void preservesConfigServerAndRideGates() {
    assertFalse(
        SpaceMountainActivationPolicy.shouldActivate(true, false, true, RideName.SPACE_MOUNTAIN));
    assertFalse(
        SpaceMountainActivationPolicy.shouldActivate(true, true, false, RideName.SPACE_MOUNTAIN));
    assertFalse(
        SpaceMountainActivationPolicy.shouldActivate(true, true, true, RideName.SPLASH_MOUNTAIN));
    assertFalse(SpaceMountainActivationPolicy.shouldActivate(true, true, true, null));
  }
}
