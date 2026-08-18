package com.chenweikeng.imf.nra.audio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SessionOfferTrackerTest {
  @Test
  void acceptedOfferCancelsItsTimeout() {
    SessionOfferTracker tracker = new SessionOfferTracker();
    long request = tracker.begin();

    assertTrue(tracker.isPending());
    assertTrue(tracker.accept());
    assertFalse(tracker.isPending());
    assertFalse(tracker.expire(request));
  }

  @Test
  void staleTimeoutCannotExpireAReplacementRequest() {
    SessionOfferTracker tracker = new SessionOfferTracker();
    long staleRequest = tracker.begin();
    long currentRequest = tracker.begin();

    assertFalse(tracker.expire(staleRequest));
    assertTrue(tracker.isPending());
    assertTrue(tracker.expire(currentRequest));
    assertFalse(tracker.isPending());
  }

  @Test
  void cancellationMakesRepeatedCleanupIdempotent() {
    SessionOfferTracker tracker = new SessionOfferTracker();
    long request = tracker.begin();

    tracker.cancel();
    tracker.cancel();

    assertFalse(tracker.isPending());
    assertFalse(tracker.expire(request));
    assertFalse(tracker.accept());
  }
}
