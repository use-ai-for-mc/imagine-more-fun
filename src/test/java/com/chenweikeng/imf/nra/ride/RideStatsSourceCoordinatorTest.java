package com.chenweikeng.imf.nra.ride;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RideStatsSourceCoordinatorTest {

  @AfterEach
  void resetConnectionState() {
    RideStatsSourceCoordinator.onDisconnect();
  }

  @Test
  void manualRideStatsRemainAuthoritativeAfterApiSnapshot() {
    RideStatsSourceCoordinator.markApiSnapshotReady();

    assertTrue(RideStatsSourceCoordinator.isApiSnapshotReady());
    assertTrue(RideStatsSourceCoordinator.shouldCaptureLegacyRideStats());
  }
}
