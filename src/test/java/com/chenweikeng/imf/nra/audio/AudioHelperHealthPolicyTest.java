package com.chenweikeng.imf.nra.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class AudioHelperHealthPolicyTest {
  @Test
  void healthySessionRemainsActive() {
    assertNull(AudioHelperHealthPolicy.recycleReason(health(400, 395, 5, 0)));
  }

  @Test
  void mediaCreationLimitForcesRecycleEvenWhenEverythingWasDisposed() {
    assertEquals(
        "media-created-limit",
        AudioHelperHealthPolicy.recycleReason(
            health(
                AudioHelperHealthPolicy.MAX_MEDIA_CREATED,
                AudioHelperHealthPolicy.MAX_MEDIA_CREATED,
                0,
                0)));
  }

  @Test
  void validLargePreloadSetDoesNotCauseImmediateRecycle() {
    assertNull(AudioHelperHealthPolicy.recycleReason(health(400, 0, 400, 0)));
  }

  @Test
  void counterInvariantRemainsFailClosed() {
    assertEquals(
        "media-counter-invariant", AudioHelperHealthPolicy.recycleReason(health(20, 10, 9, 0)));
  }

  @Test
  void errorAbortBurstWinsOverOtherReasonsForGpuRecovery() {
    assertEquals(
        "media-error-abort-burst",
        AudioHelperHealthPolicy.recycleReason(
            health(20, 10, 10, AudioHelperHealthPolicy.MAX_RECENT_ERROR_ABORT)));
  }

  private static AudioHelperHealthPolicy.MediaHealth health(
      int created, int disposed, int live, int recentErrorAbort) {
    return new AudioHelperHealthPolicy.MediaHealth(
        created, disposed, live, 100, 200, recentErrorAbort, recentErrorAbort);
  }
}
