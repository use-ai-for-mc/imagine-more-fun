package com.chenweikeng.imf.nra.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AudioRecoveryPolicyTest {
  @Test
  void failedConnectionsRetryWithBoundedExponentialBackoff() {
    AudioRecoveryPolicy.RetryDecision first = AudioRecoveryPolicy.afterFailure(0, 3, 3_000);
    AudioRecoveryPolicy.RetryDecision second =
        AudioRecoveryPolicy.afterFailure(first.nextAttempt(), 3, 3_000);
    AudioRecoveryPolicy.RetryDecision third =
        AudioRecoveryPolicy.afterFailure(second.nextAttempt(), 3, 3_000);
    AudioRecoveryPolicy.RetryDecision exhausted =
        AudioRecoveryPolicy.afterFailure(third.nextAttempt(), 3, 3_000);

    assertTrue(first.retry());
    assertEquals(1, first.nextAttempt());
    assertEquals(3_000, first.delayMs());
    assertEquals(6_000, second.delayMs());
    assertEquals(12_000, third.delayMs());
    assertFalse(exhausted.retry());
    assertEquals(3, exhausted.nextAttempt());
  }

  @Test
  void plannedRecycleWaitsBeyondObservedServerCleanupOverlap() {
    assertTrue(AudioRecoveryPolicy.SERVER_SESSION_RELEASE_DELAY_MS > 9_000);
  }

  @Test
  void manualConnectionIntentKeepsServerLifecycleManagedWhenConfigIsOff() {
    assertTrue(AudioRecoveryPolicy.shouldMaintainSession(true, false, false, false));
  }

  @Test
  void everyInFlightSessionStateKeepsServerLifecycleManaged() {
    assertTrue(AudioRecoveryPolicy.shouldMaintainSession(false, true, false, false));
    assertTrue(AudioRecoveryPolicy.shouldMaintainSession(false, false, true, false));
    assertTrue(AudioRecoveryPolicy.shouldMaintainSession(false, false, false, true));
  }

  @Test
  void explicitDisconnectStopsUnwantedSessionRegeneration() {
    assertFalse(AudioRecoveryPolicy.shouldMaintainSession(false, false, false, false));
  }

  @Test
  void runtimeIntentOrConfigCanResumeOnJoin() {
    assertFalse(AudioRecoveryPolicy.shouldConnectOnJoin(false, false));
    assertTrue(AudioRecoveryPolicy.shouldConnectOnJoin(true, false));
    assertTrue(AudioRecoveryPolicy.shouldConnectOnJoin(false, true));
  }

  @Test
  void missingOfferRetriesOnlyWhileAnUnfulfilledConnectionIsDesired() {
    assertTrue(AudioRecoveryPolicy.shouldRetryMissingSessionOffer(true, false, false));
    assertFalse(AudioRecoveryPolicy.shouldRetryMissingSessionOffer(false, false, false));
    assertFalse(AudioRecoveryPolicy.shouldRetryMissingSessionOffer(true, true, false));
    assertFalse(AudioRecoveryPolicy.shouldRetryMissingSessionOffer(true, false, true));
  }
}
