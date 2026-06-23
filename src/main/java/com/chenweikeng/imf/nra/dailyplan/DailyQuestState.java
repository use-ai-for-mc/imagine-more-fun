package com.chenweikeng.imf.nra.dailyplan;

import com.chenweikeng.imf.ImfFileIO;
import com.chenweikeng.imf.ImfStorage;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Singleton holding the most-recent {@link DailyQuestSnapshot} parsed from the in-game Daily
 * Objectives screen. Backed by {@code config/imaginemorefun/nra-daily-quests.json} so the snapshot
 * survives restarts. The plan generator queries {@link #nextEligibleForPlan} when extending the
 * tail to decide whether the next layer should be a quest layer or a randomly generated one.
 */
public final class DailyQuestState {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  /**
   * Server quest refresh schedule: every 8 hours starting at midnight Pacific (00:00, 08:00,
   * 16:00). The plan only honours a captured snapshot — and only treats a quest layer as "pinning"
   * its ride — within the same 8-hour window.
   */
  private static final ZoneId QUEST_REFRESH_ZONE = ZoneId.of("America/Los_Angeles");

  private static final int WINDOW_HOURS = 8;

  private static DailyQuestState instance;

  private DailyQuestSnapshot snapshot;
  private boolean loaded;

  private DailyQuestState() {}

  public static synchronized DailyQuestState getInstance() {
    if (instance == null) {
      instance = new DailyQuestState();
    }
    return instance;
  }

  public synchronized DailyQuestSnapshot getSnapshot() {
    if (!loaded) {
      snapshot = loadFromDisk();
      loaded = true;
    }
    return snapshot;
  }

  public synchronized void setSnapshot(DailyQuestSnapshot fresh) {
    this.snapshot = fresh;
    this.loaded = true;
    saveToDisk(fresh);
  }

  /**
   * Returns the next quest the plan should pin as a new layer, or empty if there is nothing to do
   * right now.
   *
   * <p>Decision is scoped to the <em>current 8-hour window</em>:
   *
   * <ol>
   *   <li>The snapshot is only consulted if it was captured in the same window as now (otherwise it
   *       pre-dates the server's last refresh and is stale).
   *   <li>A quest's ride only counts as already-pinned if some quest layer in the plan was added in
   *       the current window for that ride. Layers from earlier windows do not block.
   *   <li>Within the current window, layers pin regardless of local completion state — so once a
   *       quest layer is added, re-opening the Daily Objectives screen during the same window will
   *       not produce a duplicate even if the local layer auto-completed before the server credited
   *       the quest.
   * </ol>
   */
  public synchronized Optional<DailyQuest> nextEligibleForPlan(DailyPlan plan) {
    String currentWindow = currentWindowKey();
    DailyQuestSnapshot snap = getSnapshot();
    if (!isFresh(snap, currentWindow)) {
      return Optional.empty();
    }
    // The plan is a per-local-day artifact (DailyPlanManager keys regeneration off
    // LocalDate.now()),
    // but quest windows are Pacific. For a player east of Pacific, local midnight lands inside a
    // still-current PT window, so without this guard a plan regenerated at local midnight would
    // re-pin the previous local day's quest — with a baseline reset to the current ride count — as
    // its first layer, before the user re-opens Daily Objectives. Requiring a same-local-day
    // capture
    // defers pinning to the next snapshot capture, where the quest re-pins with a correct baseline.
    if (!LocalDate.now().toString().equals(snap.capturedDate)) {
      return Optional.empty();
    }
    Set<String> alreadyPinned = ridesPinnedInWindow(plan, currentWindow);
    for (DailyQuest q : snap.quests) {
      if (q == null || q.rideMatchName == null) {
        continue;
      }
      if (q.observedProgress >= q.target) {
        continue;
      }
      if (alreadyPinned.contains(q.rideMatchName)) {
        continue;
      }
      return Optional.of(q);
    }
    return Optional.empty();
  }

  /** Returns the 8h-window key for {@code now}. Public so the generator can stamp new layers. */
  public static String currentWindowKey() {
    return windowKeyFor(System.currentTimeMillis());
  }

  /**
   * Window keys look like {@code "2026-04-28-T00"} / {@code "-T08"} / {@code "-T16"}, identifying
   * the 8-hour bucket in Pacific time the timestamp falls into. Equality of two keys means
   * "captured in the same server-quest refresh window".
   */
  static String windowKeyFor(long epochMs) {
    ZonedDateTime z = Instant.ofEpochMilli(epochMs).atZone(QUEST_REFRESH_ZONE);
    int bucketHour = (z.getHour() / WINDOW_HOURS) * WINDOW_HOURS;
    return String.format("%s-T%02d", z.toLocalDate(), bucketHour);
  }

  /**
   * A snapshot is considered fresh only when it was captured during the current 8h window.
   * Reopening the Daily Objectives screen always overwrites the snapshot, so under normal play this
   * is true; once a refresh boundary passes without re-capture, the snapshot becomes stale and
   * quest injection pauses until the user looks again.
   */
  static boolean isFresh(DailyQuestSnapshot snap, String currentWindow) {
    if (snap == null || snap.quests == null || snap.quests.isEmpty()) {
      return false;
    }
    return currentWindow.equals(windowKeyFor(snap.capturedAtEpochMs));
  }

  private static Set<String> ridesPinnedInWindow(DailyPlan plan, String currentWindow) {
    Set<String> out = new HashSet<>();
    if (plan == null || plan.layers == null) {
      return out;
    }
    for (DailyPlanLayer layer : plan.layers) {
      if (!layer.fromDailyQuest) {
        continue;
      }
      if (!currentWindow.equals(layer.questWindowKey)) {
        continue;
      }
      List<DailyPlanNode> nodes = layer.nodes;
      if (nodes == null) {
        continue;
      }
      for (DailyPlanNode node : nodes) {
        if (node != null && node.ride != null) {
          out.add(node.ride);
        }
      }
    }
    return out;
  }

  private static DailyQuestSnapshot loadFromDisk() {
    Path path = ImfStorage.nraDailyQuests();
    File file = path.toFile();
    if (!file.exists()) {
      return null;
    }
    return ImfFileIO.readJson(
        path, GSON, DailyQuestSnapshot.class, NotRidingAlertClient.LOGGER, "daily quest state");
  }

  private static void saveToDisk(DailyQuestSnapshot snap) {
    if (snap == null) {
      return;
    }
    Path path = ImfStorage.nraDailyQuests();
    ImfFileIO.writeJsonAtomic(path, GSON, snap, NotRidingAlertClient.LOGGER, "daily quest state");
  }
}
