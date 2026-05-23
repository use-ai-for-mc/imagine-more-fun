package com.chenweikeng.imf.nra.dailyplan;

import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.ride.RideCountManager;
import com.chenweikeng.imf.nra.ride.RideName;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public final class DailyPlanManager {
  private static DailyPlanManager instance;

  private DailyPlan cached;
  private boolean loaded;

  private DailyPlanManager() {}

  public static synchronized DailyPlanManager getInstance() {
    if (instance == null) {
      instance = new DailyPlanManager();
    }
    return instance;
  }

  /** Returns today's plan, generating (or migrating) one if none exists or the stored one is */
  /* stale. */
  public synchronized DailyPlan getOrCreateToday() {
    LocalDate today = LocalDate.now();
    if (!loaded) {
      cached = DailyPlanStorage.load();
      loaded = true;
      if (cached != null) {
        boolean migratedNodes = migrateLegacyIfNeeded(cached);
        boolean migratedBaselines = migrateBaselinesIfNeeded(cached);
        if (migratedNodes || migratedBaselines) {
          DailyPlanStorage.save(cached);
        }
      }
    }
    boolean pruned = false;
    if (cached != null) {
      pruned = pruneHiddenRideLayers(cached);
    }

    boolean regenerated = false;
    if (cached == null
        || !today.toString().equals(cached.date)
        || cached.layers == null
        || cached.layers.isEmpty()) {
      cached = DailyPlanGenerator.generate(today);
      regenerated = true;
    }
    boolean extended = DailyPlanGenerator.ensureTailCapacity(cached);
    if (regenerated || pruned || extended) {
      DailyPlanStorage.save(cached);
    }
    return cached;
  }

  /** Re-reads from disk — useful when external state (e.g. file deleted) changes. */
  public synchronized void invalidateCache() {
    cached = null;
    loaded = false;
  }

  /**
   * Inserts a quest layer for every eligible-and-unpinned daily quest in the snapshot, placing each
   * one right after the currently active layer so the user sees it next. Without this, freshly
   * captured quests would only get pulled in when {@link DailyPlanGenerator#ensureTailCapacity}
   * naturally extends the tail — which doesn't happen until the user finishes enough random fillers
   * to drop the unfinished count below the minimum. Persists the plan if any layer was inserted.
   */
  public synchronized boolean injectPendingQuestLayers() {
    DailyPlan plan = getOrCreateToday();
    if (plan == null || plan.layers == null) {
      return false;
    }
    int insertAt = plan.layers.size();
    for (int i = 0; i < plan.layers.size(); i++) {
      if (!plan.layers.get(i).completed) {
        insertAt = i + 1;
        break;
      }
    }
    boolean anyAdded = false;
    while (true) {
      DailyPlanLayer layer = DailyPlanGenerator.buildNextDailyQuestLayer(plan);
      if (layer == null) {
        break;
      }
      plan.layers.add(insertAt, layer);
      insertAt++;
      anyAdded = true;
    }
    if (anyAdded) {
      DailyPlanStorage.save(plan);
    }
    return anyAdded;
  }

  /**
   * Snapshot-driven completion for special daily-quest layers (RIDDLE_RIDE / NPC / LAND_RIDE) —
   * they don't auto-complete via ride counts, so we reconcile against the freshly captured snapshot
   * whenever the user re-opens the Daily Objectives screen. A pending layer flips to {@code
   * completed} when its pin-key is gone from the new snapshot (server retired the quest after
   * completion) or its observedProgress has reached the target. Each flipped layer fires {@link
   * DailyPlanCelebration#layerCompleted} so the level-up sound + particles play just like a
   * normally-tracked layer. Persists the plan if anything flipped.
   */
  public synchronized void reconcileSpecialQuestLayers(
      net.minecraft.client.Minecraft client, DailyQuestSnapshot fresh) {
    if (fresh == null) {
      return;
    }
    DailyPlan plan = getOrCreateToday();
    if (plan == null || plan.layers == null) {
      return;
    }
    java.util.Map<String, DailyQuest> byKey = new java.util.HashMap<>();
    if (fresh.quests != null) {
      for (DailyQuest q : fresh.quests) {
        if (q == null || q.rideMatchName == null) {
          continue;
        }
        if (q.kindOrDefault() != DailyQuest.Kind.RIDE) {
          byKey.put(q.rideMatchName, q);
        }
      }
    }
    boolean anyChanged = false;
    for (int layerIdx = 0; layerIdx < plan.layers.size(); layerIdx++) {
      DailyPlanLayer layer = plan.layers.get(layerIdx);
      if (!layer.fromDailyQuest || layer.completed || layer.nodes == null) {
        continue;
      }
      for (DailyPlanNode node : layer.nodes) {
        if (node == null || node.ride == null || node.completed) {
          continue;
        }
        if (!isSpecialPinKey(node.ride)) {
          continue;
        }
        DailyQuest match = byKey.get(node.ride);
        boolean done = match == null || match.observedProgress >= match.target;
        if (done) {
          node.completed = true;
          anyChanged = true;
        }
      }
      if (layer.recomputeCompleted()) {
        anyChanged = true;
        DailyPlanCelebration.layerCompleted(client, layerIdx + 1, layer.type);
      }
    }
    if (anyChanged) {
      DailyPlanStorage.save(plan);
    }
  }

  /**
   * Removes uncompleted RIDE-kind daily-quest layers whose ride is absent from a freshly captured
   * snapshot. The IF server never resets daily objectives — they only leave the list when the
   * player completes them — so a pinned ride quest dropping out of the snapshot means it was
   * completed on the server. Genuine in-session completions are already flipped to {@code
   * completed} by count-based progress tracking and are skipped here (this only touches uncompleted
   * layers); so what this catches is a stale re-pin whose baseline was reset — e.g. a quest the
   * user finished yesterday that got re-pinned across the local-midnight plan rollover and now
   * demands fresh rides the server isn't asking for.
   *
   * <p>Guarded to a snapshot captured on the current local day so a stale (prior-day) reading can't
   * drop a still-valid quest. Special kinds (riddle / npc / land / task) are left to {@link
   * #reconcileSpecialQuestLayers}. Persists the plan if anything was removed.
   */
  public synchronized boolean pruneCompletedRideQuestLayers(DailyQuestSnapshot fresh) {
    if (fresh == null || !LocalDate.now().toString().equals(fresh.capturedDate)) {
      return false;
    }
    DailyPlan plan = getOrCreateToday();
    if (plan == null || plan.layers == null) {
      return false;
    }
    Set<String> liveRideKeys = new HashSet<>();
    if (fresh.quests != null) {
      for (DailyQuest q : fresh.quests) {
        if (q != null && q.rideMatchName != null && q.kindOrDefault() == DailyQuest.Kind.RIDE) {
          liveRideKeys.add(q.rideMatchName);
        }
      }
    }
    boolean anyRemoved = false;
    for (int i = plan.layers.size() - 1; i >= 0; i--) {
      DailyPlanLayer layer = plan.layers.get(i);
      if (!layer.fromDailyQuest || layer.completed || layer.nodes == null) {
        continue;
      }
      boolean anyRideNode = false;
      boolean allRideNodesAbsent = true;
      for (DailyPlanNode node : layer.nodes) {
        if (node == null || node.ride == null) {
          continue;
        }
        if (isSpecialPinKey(node.ride)) {
          allRideNodesAbsent = false; // special kinds reconcile elsewhere
          break;
        }
        anyRideNode = true;
        if (liveRideKeys.contains(node.ride)) {
          allRideNodesAbsent = false;
          break;
        }
      }
      if (anyRideNode && allRideNodesAbsent) {
        plan.layers.remove(i);
        anyRemoved = true;
      }
    }
    if (anyRemoved) {
      DailyPlanStorage.save(plan);
    }
    // The plan is topped back up to its usual size by DailyPlanGenerator.ensureTailCapacity, which
    // every getOrCreateToday() call runs — including the injectPendingQuestLayers() that follows
    // this prune in the capture flow — so a removed ghost doesn't leave the plan short.
    return anyRemoved;
  }

  private static boolean isSpecialPinKey(String pinKey) {
    return pinKey.startsWith(":riddle:")
        || pinKey.startsWith(":npc:")
        || pinKey.startsWith(":land:")
        || pinKey.startsWith(":task:");
  }

  /**
   * Removes uncompleted daily-quest layers from <em>prior</em> 8h windows when the same quest
   * appears again in the fresh current-window snapshot — those are stale duplicates of the layer
   * that {@link #injectPendingQuestLayers} will (or already did) pin for the current window. The
   * server refreshes quests every 8h but routinely re-issues the same goal; a previous window's
   * uncompleted "Mainst 4x" pin — or "Critter Country 3x" land challenge — is the same goal the
   * server is asking for again, not an extra one.
   *
   * <p>Applies to <em>every</em> quest kind. RIDE quests dedup on the canonical ride match-name;
   * the special kinds (riddle / npc / land / task) dedup on their sentinel key, which {@link
   * DailyQuestParser} derives deterministically from stable quest content, so the same quest yields
   * the same key across re-captures. Earlier this skipped the special kinds — leaving a land/npc
   * quest that straddled a window boundary pinned twice (one "current", one "next") with nothing to
   * collapse it, since {@link #reconcileSpecialQuestLayers} only flips layers to completed and
   * never removes duplicates.
   *
   * <p>Without this, opening /dailies across windows accumulates duplicate pins (e.g. two Critter
   * Country tiles in the Ride Plan, one tagged to each window). Completed layers are left untouched
   * so they survive as history. Persists the plan when anything was removed.
   */
  public synchronized boolean pruneStalePriorQuestLayers(DailyQuestSnapshot fresh) {
    if (fresh == null || fresh.quests == null || fresh.quests.isEmpty()) {
      return false;
    }
    DailyPlan plan = getOrCreateToday();
    if (plan == null || plan.layers == null) {
      return false;
    }
    String currentWindow = DailyQuestState.currentWindowKey();
    Set<String> currentQuestKeys = new HashSet<>();
    for (DailyQuest q : fresh.quests) {
      if (q == null || q.rideMatchName == null) {
        continue;
      }
      currentQuestKeys.add(q.rideMatchName);
    }
    if (currentQuestKeys.isEmpty()) {
      return false;
    }
    boolean anyRemoved = false;
    for (int i = plan.layers.size() - 1; i >= 0; i--) {
      DailyPlanLayer layer = plan.layers.get(i);
      if (!layer.fromDailyQuest || layer.completed || layer.nodes == null) {
        continue;
      }
      if (currentWindow.equals(layer.questWindowKey)) {
        continue;
      }
      boolean stale = false;
      for (DailyPlanNode node : layer.nodes) {
        if (node == null || node.ride == null) {
          continue;
        }
        if (currentQuestKeys.contains(node.ride)) {
          stale = true;
          break;
        }
      }
      if (stale) {
        plan.layers.remove(i);
        anyRemoved = true;
      }
    }
    if (anyRemoved) {
      DailyPlanStorage.save(plan);
    }
    return anyRemoved;
  }

  /**
   * Wraps each legacy {@code nodes} entry in a SINGLE layer, preserving {@code completed} state.
   * Returns true if migration happened and the caller should persist.
   */
  /**
   * Pre-gated-era plans have no per-layer baselines. Seed the active layer and all earlier ones
   * from {@code plan.snapshotCounts} so their visible progress doesn't reset; leave later layers
   * null so gating picks up from the next activation.
   */
  private static boolean migrateBaselinesIfNeeded(DailyPlan plan) {
    if (plan.layers == null || plan.layers.isEmpty()) {
      return false;
    }
    boolean anyBaselineSet = false;
    for (DailyPlanLayer layer : plan.layers) {
      if (layer.baselineCounts != null) {
        anyBaselineSet = true;
        break;
      }
    }
    if (anyBaselineSet) {
      return false;
    }
    int activeIdx = -1;
    for (int i = 0; i < plan.layers.size(); i++) {
      if (!plan.layers.get(i).completed) {
        activeIdx = i;
        break;
      }
    }
    int endIdx = activeIdx == -1 ? plan.layers.size() - 1 : activeIdx;
    java.util.Map<String, Integer> snap =
        plan.snapshotCounts == null ? new HashMap<>() : plan.snapshotCounts;
    for (int i = 0; i <= endIdx; i++) {
      plan.layers.get(i).baselineCounts = new HashMap<>(snap);
    }
    return true;
  }

  private static boolean migrateLegacyIfNeeded(DailyPlan plan) {
    if (plan.layers != null && !plan.layers.isEmpty()) {
      plan.nodes = null;
      return false;
    }
    if (plan.nodes == null || plan.nodes.isEmpty()) {
      return false;
    }
    plan.layers = new ArrayList<>(plan.nodes.size());
    for (DailyPlanNode node : plan.nodes) {
      plan.layers.add(DailyPlanLayer.single(node));
    }
    plan.nodes = null;
    return true;
  }

  /**
   * Removes uncompleted layers whose nodes reference rides the user has hidden via the Rides tab.
   * The active (first uncompleted) layer is only pruned when no progress has been made on any of
   * its nodes; upcoming layers are always pruned. Completed layers survive unchanged.
   */
  private static boolean pruneHiddenRideLayers(DailyPlan plan) {
    if (plan == null || plan.layers == null || plan.layers.isEmpty()) {
      return false;
    }
    Set<String> hiddenRides = ModConfig.currentSetting.hiddenRides;
    if (hiddenRides == null || hiddenRides.isEmpty()) {
      return false;
    }

    int activeIdx = -1;
    for (int i = 0; i < plan.layers.size(); i++) {
      if (!plan.layers.get(i).completed) {
        activeIdx = i;
        break;
      }
    }
    if (activeIdx < 0) {
      return false;
    }

    boolean anyRemoved = false;
    RideCountManager counts = RideCountManager.getInstance();

    DailyPlanLayer active = plan.layers.get(activeIdx);
    if (!active.fromDailyQuest
        && layerContainsHiddenRide(active, hiddenRides)
        && !hasProgress(active, counts)) {
      plan.layers.remove(activeIdx);
      anyRemoved = true;
      activeIdx = -1;
      for (int i = 0; i < plan.layers.size(); i++) {
        if (!plan.layers.get(i).completed) {
          activeIdx = i;
          break;
        }
      }
    }

    if (activeIdx >= 0) {
      for (int i = plan.layers.size() - 1; i > activeIdx; i--) {
        DailyPlanLayer layer = plan.layers.get(i);
        if (!layer.completed
            && !layer.fromDailyQuest
            && layerContainsHiddenRide(layer, hiddenRides)) {
          plan.layers.remove(i);
          anyRemoved = true;
        }
      }
    }

    return anyRemoved;
  }

  private static boolean layerContainsHiddenRide(DailyPlanLayer layer, Set<String> hiddenRides) {
    if (layer == null || layer.nodes == null) {
      return false;
    }
    for (DailyPlanNode node : layer.nodes) {
      if (node != null && hiddenRides.contains(node.ride)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasProgress(DailyPlanLayer layer, RideCountManager counts) {
    if (layer == null || layer.nodes == null) {
      return false;
    }
    for (DailyPlanNode node : layer.nodes) {
      if (node == null || node.completed) {
        continue;
      }
      RideName ride = RideName.fromMatchString(node.ride);
      if (ride == RideName.UNKNOWN) {
        continue;
      }
      int baseline = 0;
      if (layer.baselineCounts != null) {
        baseline = layer.baselineCounts.getOrDefault(node.ride, 0);
      }
      if (counts.getRideCount(ride) > baseline) {
        return true;
      }
    }
    return false;
  }
}
