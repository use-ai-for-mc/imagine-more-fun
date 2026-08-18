package com.chenweikeng.imf.nra.audio;

/** Keeps the user's OpenAudioMC volume across isolated helper generations. */
final class AudioVolumeState {
  static final int UNKNOWN = -1;

  record Observation(int effectiveVolume, boolean restoreRequired, boolean persistenceRequired) {}

  private int preferredVolume = UNKNOWN;
  private boolean restorePending;
  private boolean restoreInFlight;

  AudioVolumeState() {}

  AudioVolumeState(int persistedVolume) {
    if (isValid(persistedVolume)) {
      preferredVolume = persistedVolume;
    }
  }

  synchronized void sessionStarted() {
    restorePending = isValid(preferredVolume);
    restoreInFlight = false;
  }

  synchronized void sessionStopped() {
    restorePending = false;
    restoreInFlight = false;
  }

  synchronized Observation observePageVolume(int pageVolume) {
    if (!isValid(pageVolume)) {
      return new Observation(UNKNOWN, false, false);
    }

    if (!restorePending) {
      boolean persistenceRequired = isValid(preferredVolume) && preferredVolume != pageVolume;
      preferredVolume = pageVolume;
      return new Observation(pageVolume, false, persistenceRequired);
    }

    int targetVolume = preferredVolume;
    if (pageVolume == targetVolume) {
      restorePending = false;
      restoreInFlight = false;
      return new Observation(targetVolume, false, false);
    }
    if (restoreInFlight) {
      return new Observation(targetVolume, false, false);
    }

    restoreInFlight = true;
    return new Observation(targetVolume, true, false);
  }

  synchronized void recordExplicitVolume(int volume) {
    if (!isValid(volume)) {
      return;
    }
    preferredVolume = volume;
    restorePending = false;
    restoreInFlight = false;
  }

  synchronized void recordExternalVolume(int volume) {
    if (!isValid(volume)) {
      return;
    }
    preferredVolume = volume;
    restorePending = true;
    restoreInFlight = true;
  }

  synchronized void restoreCompleted(boolean success, int actualVolume) {
    restoreInFlight = false;
    if (success && isValid(actualVolume)) {
      preferredVolume = actualVolume;
      restorePending = false;
    }
  }

  synchronized int preferredVolume() {
    return preferredVolume;
  }

  static boolean isValid(int volume) {
    return volume >= 0 && volume <= 100;
  }
}
