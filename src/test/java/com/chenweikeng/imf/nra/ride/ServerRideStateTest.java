package com.chenweikeng.imf.nra.ride;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ServerRideStateTest {
  @Test
  void followsEachServerIdentityWithoutSeasonalLatch() {
    ServerRideState state = new ServerRideState();
    for (String id : new String[] {"hmh", "hm", "gotgmad", "guardians", "gotgmad"}) {
      state.update(id, true, 1000);
      assertTrue(state.isAuthoritative());
      assertEquals(RideName.fromApiId(id), state.getRide());
      assertEquals(12, state.elapsedSeconds(13_999));
      state.update(id, false, 1000);
      assertTrue(state.isAuthoritative());
      assertNull(state.getRide());
      assertNull(state.elapsedSeconds(14_000));
    }
  }

  @Test
  void resetsAuthorityAcrossConnectionsAndPreservesUnknownIdentity() {
    ServerRideState state = new ServerRideState();
    assertFalse(state.isAuthoritative());
    state.update("new-seasonal-ride", true, 0);
    assertTrue(state.isAuthoritative());
    assertEquals(RideName.UNKNOWN, state.getRide());
    assertNull(state.elapsedSeconds(1000));
    state.reset();
    assertFalse(state.isAuthoritative());
    assertNull(state.getRide());
    state.update("hmh", true, 5000);
    assertEquals(0, state.elapsedSeconds(4000));
  }

  @Test
  void sidebarOnlyDistinguishesVariantsWhenEnoughOfNameIsAvailable() {
    assertEquals(RideName.UNKNOWN, RideName.fromTruncatedString(" | Haunted Mansi..."));
    assertEquals(RideName.UNKNOWN, RideName.fromTruncatedString("Haunted Mansion..."));
    assertEquals(RideName.UNKNOWN, RideName.fromTruncatedString("Guardians of th..."));
    assertEquals(RideName.HAUNTED_MANSION, RideName.fromTruncatedString("Haunted Mansion"));
    assertEquals(
        RideName.HAUNTED_MANSION_HOLIDAY, RideName.fromTruncatedString("Haunted Mansion Hol..."));
    assertEquals(
        RideName.GUARDIANS_OF_THE_GALAXY_MONSTERS_AFTER_DARK,
        RideName.fromTruncatedString("Guardians of the Galaxy: Monsters..."));
    assertEquals(RideName.SPLASH_MOUNTAIN, RideName.fromTruncatedString("⏐ Splash Mount..."));
  }
}
