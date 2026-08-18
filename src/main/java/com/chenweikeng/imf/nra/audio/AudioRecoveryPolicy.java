package com.chenweikeng.imf.nra.audio;

/** Retry timing for releasing an old server-side OpenAudioMC session and creating a new one. */
final class AudioRecoveryPolicy {
  /**
   * The server can emit its final "session ended" several seconds after the old helper socket has
   * closed. Requesting and attaching a replacement inside that window lets the delayed cleanup
   * terminate the replacement instead. The observed overlap was nine seconds.
   */
  static final long SERVER_SESSION_RELEASE_DELAY_MS = 15_000;

  record RetryDecision(boolean retry, int nextAttempt, long delayMs) {}

  private AudioRecoveryPolicy() {}

  static boolean shouldMaintainSession(
      boolean connectionDesired,
      boolean active,
      boolean pendingCommandConnect,
      boolean pendingRecovery) {
    return connectionDesired || active || pendingCommandConnect || pendingRecovery;
  }

  static boolean shouldConnectOnJoin(boolean configuredAutoConnect, boolean runtimeIntent) {
    return configuredAutoConnect || runtimeIntent;
  }

  static boolean shouldRetryMissingSessionOffer(
      boolean connectionDesired, boolean active, boolean helperStarting) {
    return connectionDesired && !active && !helperStarting;
  }

  static RetryDecision afterFailure(int completedAttempts, int maxAttempts, long baseDelayMs) {
    if (completedAttempts >= maxAttempts) {
      return new RetryDecision(false, completedAttempts, 0);
    }
    int nextAttempt = completedAttempts + 1;
    long delayMs = baseDelayMs * (1L << (nextAttempt - 1));
    return new RetryDecision(true, nextAttempt, delayMs);
  }
}
