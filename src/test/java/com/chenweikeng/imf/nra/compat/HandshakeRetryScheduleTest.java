package com.chenweikeng.imf.nra.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HandshakeRetryScheduleTest {
  @Test
  void retriesAtFiveFifteenAndThirtyFiveSecondsThenStops() {
    HandshakeRetrySchedule schedule = new HandshakeRetrySchedule();
    schedule.onJoin(1_000L);

    assertFalse(schedule.shouldRetry(5_999L, false));
    assertTrue(schedule.shouldRetry(6_000L, false));
    assertEquals(1, schedule.markRetrySent(6_000L));

    assertFalse(schedule.shouldRetry(15_999L, false));
    assertTrue(schedule.shouldRetry(16_000L, false));
    assertEquals(2, schedule.markRetrySent(16_000L));

    assertFalse(schedule.shouldRetry(35_999L, false));
    assertTrue(schedule.shouldRetry(36_000L, false));
    assertEquals(3, schedule.markRetrySent(36_000L));

    assertFalse(schedule.shouldRetry(Long.MAX_VALUE, false));
    assertFalse(schedule.shouldReportExhausted(40_999L, false));
    assertTrue(schedule.shouldReportExhausted(41_000L, false));
    assertFalse(schedule.shouldReportExhausted(Long.MAX_VALUE, false));
    assertEquals(3, schedule.retriesSent());
  }

  @Test
  void activeSessionCancelsRemainingRetries() {
    HandshakeRetrySchedule schedule = new HandshakeRetrySchedule();
    schedule.onJoin(0L);

    assertFalse(schedule.shouldRetry(5_000L, true));
    assertFalse(schedule.shouldRetry(Long.MAX_VALUE, false));
    assertFalse(schedule.shouldReportExhausted(Long.MAX_VALUE, false));
    assertEquals(0, schedule.retriesSent());
  }

  @Test
  void reconnectStartsACompleteNewBoundedSequence() {
    HandshakeRetrySchedule schedule = new HandshakeRetrySchedule();
    schedule.onJoin(0L);
    schedule.markRetrySent(5_000L);
    schedule.markRetrySent(15_000L);

    schedule.onJoin(100_000L);

    assertEquals(0, schedule.retriesSent());
    assertFalse(schedule.shouldRetry(104_999L, false));
    assertTrue(schedule.shouldRetry(105_000L, false));
  }
}
