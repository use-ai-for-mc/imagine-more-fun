package com.chenweikeng.imf.nra.compat;

/** Bounded retry timing for recovering a missing ImagineFunUtils API session handshake. */
final class HandshakeRetrySchedule {
  static final int MAX_RETRIES = 3;
  private static final long[] RETRY_DELAYS_MS = {5_000L, 10_000L, 20_000L};
  private static final long FINAL_RESPONSE_GRACE_MS = 5_000L;

  private int retriesSent;
  private long nextRetryAtMs = Long.MAX_VALUE;
  private long exhaustionCheckAtMs = Long.MAX_VALUE;
  private boolean retryScheduled;
  private boolean exhaustionReported;

  void onJoin(long nowMs) {
    retriesSent = 0;
    nextRetryAtMs = nowMs + RETRY_DELAYS_MS[0];
    exhaustionCheckAtMs = Long.MAX_VALUE;
    retryScheduled = true;
    exhaustionReported = false;
  }

  void stop() {
    nextRetryAtMs = Long.MAX_VALUE;
    exhaustionCheckAtMs = Long.MAX_VALUE;
    retryScheduled = false;
  }

  boolean shouldRetry(long nowMs, boolean sessionActive) {
    if (sessionActive) {
      stop();
      return false;
    }
    return retryScheduled && retriesSent < MAX_RETRIES && nowMs >= nextRetryAtMs;
  }

  int markRetrySent(long nowMs) {
    retriesSent++;
    retryScheduled = retriesSent < MAX_RETRIES;
    nextRetryAtMs = retryScheduled ? nowMs + RETRY_DELAYS_MS[retriesSent] : Long.MAX_VALUE;
    if (!retryScheduled) {
      exhaustionCheckAtMs = nowMs + FINAL_RESPONSE_GRACE_MS;
    }
    return retriesSent;
  }

  boolean shouldReportExhausted(long nowMs, boolean sessionActive) {
    if (sessionActive) {
      stop();
      return false;
    }
    if (exhaustionCheckAtMs == Long.MAX_VALUE
        || exhaustionReported
        || nowMs < exhaustionCheckAtMs) {
      return false;
    }
    exhaustionReported = true;
    return true;
  }

  int retriesSent() {
    return retriesSent;
  }
}
