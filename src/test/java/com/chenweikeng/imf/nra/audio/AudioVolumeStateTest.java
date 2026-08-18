package com.chenweikeng.imf.nra.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AudioVolumeStateTest {
  @Test
  void firstSessionLearnsPageVolumeWithoutOverridingIt() {
    AudioVolumeState state = new AudioVolumeState();

    state.sessionStarted();
    AudioVolumeState.Observation observation = state.observePageVolume(35);

    assertEquals(35, observation.effectiveVolume());
    assertFalse(observation.restoreRequired());
    assertFalse(observation.persistenceRequired());
    assertEquals(35, state.preferredVolume());
  }

  @Test
  void serverVolumeChangeIsRestoredAfterHelperRecycle() {
    AudioVolumeState state = new AudioVolumeState();
    state.sessionStarted();
    state.observePageVolume(35);
    state.observePageVolume(15);

    state.sessionStopped();
    state.sessionStarted();
    AudioVolumeState.Observation recycledPage = state.observePageVolume(35);

    assertEquals(15, recycledPage.effectiveVolume());
    assertTrue(recycledPage.restoreRequired());

    state.restoreCompleted(true, 15);
    AudioVolumeState.Observation restoredPage = state.observePageVolume(15);
    assertEquals(15, restoredPage.effectiveVolume());
    assertFalse(restoredPage.restoreRequired());
  }

  @Test
  void failedRestoreRetriesWithoutStackingRequests() {
    AudioVolumeState state = new AudioVolumeState();
    state.recordExplicitVolume(0);
    state.sessionStarted();

    assertTrue(state.observePageVolume(35).restoreRequired());
    assertFalse(state.observePageVolume(35).restoreRequired());

    state.restoreCompleted(false, AudioVolumeState.UNKNOWN);
    assertTrue(state.observePageVolume(35).restoreRequired());
  }

  @Test
  void explicitOptionsVolumeSurvivesSessionBoundary() {
    AudioVolumeState state = new AudioVolumeState();
    state.recordExplicitVolume(72);

    state.sessionStopped();
    state.sessionStarted();
    AudioVolumeState.Observation observation = state.observePageVolume(35);

    assertEquals(72, observation.effectiveVolume());
    assertTrue(observation.restoreRequired());
  }

  @Test
  void persistedZeroIsRestoredOnFirstSession() {
    AudioVolumeState state = new AudioVolumeState(0);

    state.sessionStarted();
    AudioVolumeState.Observation observation = state.observePageVolume(35);

    assertEquals(0, observation.effectiveVolume());
    assertTrue(observation.restoreRequired());
    assertFalse(observation.persistenceRequired());
  }

  @Test
  void serverVolumeChangeRequestsPersistenceAfterInitialObservation() {
    AudioVolumeState state = new AudioVolumeState();
    state.sessionStarted();

    assertFalse(state.observePageVolume(35).persistenceRequired());
    AudioVolumeState.Observation changed = state.observePageVolume(0);

    assertEquals(0, changed.effectiveVolume());
    assertFalse(changed.restoreRequired());
    assertTrue(changed.persistenceRequired());
  }

  @Test
  void serverConfirmationKeepsStalePageFromOverwritingPreference() {
    AudioVolumeState state = new AudioVolumeState(35);
    state.sessionStarted();
    state.observePageVolume(35);

    state.recordExternalVolume(0);
    AudioVolumeState.Observation stalePage = state.observePageVolume(35);

    assertEquals(0, stalePage.effectiveVolume());
    assertFalse(stalePage.restoreRequired());
    assertFalse(stalePage.persistenceRequired());
    state.restoreCompleted(false, AudioVolumeState.UNKNOWN);
    assertTrue(state.observePageVolume(35).restoreRequired());
  }
}
