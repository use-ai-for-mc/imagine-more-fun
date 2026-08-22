package com.chenweikeng.imf.nra.compat;

import com.chenweikeng.imf.ImfClient;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.ride.RideCountManager;
import com.chenweikeng.imf.nra.ride.RideName;
import com.chenweikeng.imf.nra.ride.RideStatsSourceCoordinator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import net.imaginefun.api.ImagineFunApi;
import net.imaginefun.api.ImagineFunClientEvents;
import net.imaginefun.api.model.RideStats;
import net.imaginefun.api.model.SessionPlayer;
import net.imaginefun.api.model.SessionRides;
import net.imaginefun.session.ApiSession;
import net.minecraft.client.Minecraft;

/** ImagineFunUtils-backed ride-count source. This class is loaded only when that mod is present. */
public final class ImagineFunUtilsRideDataSource implements RideStatsSourceCoordinator.ApiBridge {
  private static final long FIRST_JOIN_ATTEMPT_DELAY_MS = 3_000L;
  private static final long RIDE_END_REFRESH_DELAY_MS = 1_500L;
  private static final long PERIODIC_REFRESH_MS = 60_000L;
  private static final long[] RETRY_DELAYS_MS = {2_000L, 5_000L, 10_000L, 30_000L, 60_000L};

  private final Set<String> loggedUnknownRideIds = new HashSet<>();

  private long generation;
  private long nextAttemptAtMs = Long.MAX_VALUE;
  private int consecutiveFailures;
  private boolean connected;
  private boolean requestInFlight;

  public ImagineFunUtilsRideDataSource() {}

  @Override
  public void initialize() {
    ImagineFunClientEvents.SESSION_UPDATED.register(
        payload -> Minecraft.getInstance().execute(this::onApiSessionUpdated));
    ImagineFunClientEvents.RIDE_STATUS.register(
        payload -> {
          if (!payload.riding()) {
            Minecraft.getInstance()
                .execute(
                    () -> scheduleRefresh(System.currentTimeMillis() + RIDE_END_REFRESH_DELAY_MS));
          }
        });
  }

  private void onApiSessionUpdated() {
    if (!connected) {
      return;
    }
    generation++;
    requestInFlight = false;
    consecutiveFailures = 0;
    nextAttemptAtMs = System.currentTimeMillis();
  }

  @Override
  public void onJoin(Minecraft client) {
    generation++;
    connected = true;
    requestInFlight = false;
    consecutiveFailures = 0;
    nextAttemptAtMs = System.currentTimeMillis() + FIRST_JOIN_ATTEMPT_DELAY_MS;
  }

  @Override
  public void onDisconnect() {
    generation++;
    connected = false;
    requestInFlight = false;
    consecutiveFailures = 0;
    nextAttemptAtMs = Long.MAX_VALUE;
  }

  @Override
  public void tick(Minecraft client) {
    if (!connected
        || requestInFlight
        || client.player == null
        || System.currentTimeMillis() < nextAttemptAtMs
        || !ApiSession.isActive()) {
      return;
    }
    requestSnapshot(client);
  }

  private void scheduleRefresh(long attemptAtMs) {
    if (connected && attemptAtMs < nextAttemptAtMs) {
      nextAttemptAtMs = attemptAtMs;
    }
  }

  private void requestSnapshot(Minecraft client) {
    requestInFlight = true;
    nextAttemptAtMs = Long.MAX_VALUE;
    long requestGeneration = generation;
    String expectedUuid = normalizeUuid(client.player.getUUID().toString());

    ImagineFunApi.getSessionPlayer(ImfClient.MOD_ID)
        .thenCombine(
            ImagineFunApi.getSessionRides(ImfClient.MOD_ID),
            (player, rides) -> new ApiSnapshot(player, rides))
        .whenComplete(
            (snapshot, error) ->
                client.execute(
                    () -> completeRequest(requestGeneration, expectedUuid, snapshot, error)));
  }

  private void completeRequest(
      long requestGeneration, String expectedUuid, ApiSnapshot snapshot, Throwable error) {
    if (!connected || requestGeneration != generation) {
      return;
    }

    requestInFlight = false;
    if (error != null) {
      onFailure(error);
      return;
    }
    if (snapshot == null || snapshot.player() == null || snapshot.rides() == null) {
      onFailure(new IllegalStateException("ImagineFun API returned an incomplete ride snapshot"));
      return;
    }

    String actualUuid = normalizeUuid(snapshot.player().uuid());
    if (!expectedUuid.equals(actualUuid)) {
      onFailure(
          new IllegalStateException(
              "ImagineFun API player UUID does not match the active Minecraft player"));
      return;
    }

    Map<RideName, Integer> counts = convertCounts(snapshot.rides());
    int changed = RideCountManager.getInstance().applyServerRideCounts(counts);
    RideStatsSourceCoordinator.markApiSnapshotReady();
    consecutiveFailures = 0;
    nextAttemptAtMs = System.currentTimeMillis() + PERIODIC_REFRESH_MS;

    NotRidingAlertClient.LOGGER.info(
        "Synced {} recognized ride counts from ImagineFun API for {} ({} changed)",
        counts.size(),
        snapshot.player().username(),
        changed);
  }

  private Map<RideName, Integer> convertCounts(SessionRides sessionRides) {
    Map<RideName, Integer> counts = new HashMap<>();
    if (sessionRides.rides() == null) {
      return counts;
    }

    for (Map.Entry<String, RideStats> entry : sessionRides.rides().entrySet()) {
      String apiId = entry.getKey();
      RideName ride = RideName.fromApiId(apiId);
      if (ride == RideName.UNKNOWN) {
        logUnknownRideId(apiId);
        continue;
      }

      RideStats stats = entry.getValue();
      if (stats == null || stats.overall() == null) {
        continue;
      }
      long count = stats.overall().count();
      if (count < 0 || count > Integer.MAX_VALUE) {
        NotRidingAlertClient.LOGGER.warn(
            "Ignoring invalid ImagineFun API count {} for ride {}", count, apiId);
        continue;
      }
      counts.put(ride, (int) count);
    }
    return counts;
  }

  private void logUnknownRideId(String apiId) {
    String stableId = apiId == null ? "<null>" : apiId;
    if (loggedUnknownRideIds.add(stableId)) {
      NotRidingAlertClient.LOGGER.warn(
          "ImagineFun API returned unknown ride ID {}; preserving it as unknown", stableId);
    }
  }

  private void onFailure(Throwable error) {
    Throwable cause = unwrap(error);
    long retryDelay = RETRY_DELAYS_MS[Math.min(consecutiveFailures, RETRY_DELAYS_MS.length - 1)];
    consecutiveFailures++;
    nextAttemptAtMs = System.currentTimeMillis() + retryDelay;
    NotRidingAlertClient.LOGGER.warn(
        "ImagineFun API ride-count sync failed; keeping cached counts and retrying in {}s: {}",
        retryDelay / 1_000L,
        cause.getMessage());
  }

  private static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while (current instanceof CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static String normalizeUuid(String uuid) {
    return uuid == null ? "" : uuid.replace("-", "").toLowerCase(Locale.ROOT);
  }

  private record ApiSnapshot(SessionPlayer player, SessionRides rides) {}
}
