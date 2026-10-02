package com.chenweikeng.imf.nra.ride;

/** Connection-local ride identity from server events; no calendar or seasonal override. */
public final class ServerRideState {
  private static final ServerRideState INSTANCE = new ServerRideState();

  private boolean authoritative;
  private RideName ride;
  private long startedAtEpochMs;

  public static ServerRideState getInstance() {
    return INSTANCE;
  }

  public void update(String apiId, boolean riding, long startedAtEpochMs) {
    authoritative = true;
    ride = riding ? RideName.fromApiId(apiId) : null;
    this.startedAtEpochMs = startedAtEpochMs;
  }

  public boolean isAuthoritative() {
    return authoritative;
  }

  public RideName getRide() {
    return ride;
  }

  public Integer elapsedSeconds(long nowMs) {
    if (ride == null || startedAtEpochMs <= 0) {
      return null;
    }
    return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, nowMs - startedAtEpochMs) / 1000L);
  }

  public void reset() {
    authoritative = false;
    ride = null;
    startedAtEpochMs = 0;
  }
}
