package com.chenweikeng.imf.nra.audio;

/** Evidence-driven fail-safe for a long-lived macOS WebKit audio helper. */
final class AudioHelperHealthPolicy {
  static final int MAX_MEDIA_CREATED = 750;
  static final int MAX_RECENT_ERROR_ABORT = 12;

  record MediaHealth(
      int created,
      int disposed,
      int live,
      long lastSuccessfulPlayAt,
      long lastEndedAt,
      int recentErrorAbort,
      int totalErrorAbort) {}

  private AudioHelperHealthPolicy() {}

  static String recycleReason(MediaHealth health) {
    if (health == null) {
      return null;
    }
    if (health.recentErrorAbort() >= MAX_RECENT_ERROR_ABORT) {
      return "media-error-abort-burst";
    }
    if (health.created() < health.disposed()
        || health.created() - health.disposed() != health.live()) {
      return "media-counter-invariant";
    }
    if (health.created() >= MAX_MEDIA_CREATED) {
      return "media-created-limit";
    }
    return null;
  }
}
