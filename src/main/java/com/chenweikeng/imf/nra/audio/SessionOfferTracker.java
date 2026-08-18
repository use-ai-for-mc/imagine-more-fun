package com.chenweikeng.imf.nra.audio;

/** Tracks one outstanding server request for a fresh OpenAudioMC session URL. */
final class SessionOfferTracker {
  private long generation;
  private boolean pending;

  synchronized long begin() {
    pending = true;
    return ++generation;
  }

  synchronized boolean accept() {
    boolean wasPending = pending;
    pending = false;
    return wasPending;
  }

  synchronized boolean expire(long requestGeneration) {
    if (!pending || generation != requestGeneration) {
      return false;
    }
    pending = false;
    return true;
  }

  synchronized void cancel() {
    pending = false;
  }

  synchronized boolean isPending() {
    return pending;
  }
}
