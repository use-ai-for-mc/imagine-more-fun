package com.chenweikeng.imf.nra.ride;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CurrentRideHolderRemainingTest {
  @Test
  void remainingUsesScoreboardElapsedNotPercentReconstruction() {
    assertEquals(908, CurrentRideHolder.remainingSeconds(RideName.DISNEYLAND_RAILROAD, 100));
  }

  @Test
  void remainingClampsAtZeroAfterRideTime() {
    assertEquals(0, CurrentRideHolder.remainingSeconds(RideName.DISNEYLAND_RAILROAD, 1008));
    assertEquals(0, CurrentRideHolder.remainingSeconds(RideName.DISNEYLAND_RAILROAD, 2000));
  }

  @Test
  void remainingIsAbsentWithoutElapsedOrRide() {
    assertNull(CurrentRideHolder.remainingSeconds(null, 10));
    assertNull(CurrentRideHolder.remainingSeconds(RideName.DISNEYLAND_RAILROAD, null));
  }
}
