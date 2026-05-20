package com.chenweikeng.imf.nra.dailyplan;

import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.dailyplan.DailyPlanLayer.LayerType;
import com.chenweikeng.imf.nra.ride.AutograbHolder;
import com.chenweikeng.imf.nra.ride.RideCountManager;
import com.chenweikeng.imf.nra.ride.RideName;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Generator for {@link DailyPlan}s. Emits SINGLE / OR / AND layers (2-of-3 is retired) honouring a
 * "no repeat within 2 layers" constraint. The plan is infinite — {@link DailyPlanProgressTracker}
 * calls {@link #appendLayers} to top up the tail as the player progresses, so a session never runs
 * out of next steps.
 *
 * <p>Ride {@code k} varies with ride duration:
 *
 * <ul>
 *   <li>&gt; 10 min → k = 1
 *   <li>5–10 min → k ∈ {2, 3}
 *   <li>&lt; 5 min → k ∈ {2, 3, 4, 5}
 * </ul>
 *
 * <p>Enchanted Tiki Room and Red Car Trolley are {@link #COMPANION_REQUIRED} and never appear as
 * SINGLE-layer nodes — they only surface paired in OR/AND branches.
 *
 * <p>AND layers require one of the {@link #AND_ANCHORS} (Red Car Trolley, Enchanted Tiki Room, or
 * Great Moments with Mr. Lincoln). A naturally generated AND whose picks lack an anchor is demoted
 * to OR — those three short rides are the only ones whose pairing is cheap enough to demand both.
 */
public final class DailyPlanGenerator {
  public static final int INITIAL_LAYER_COUNT = 5;
  public static final int MIN_UNFINISHED_TAIL = 3;

  private static final int NO_REPEAT_WINDOW = 2;

  /** Rides that must never appear as the sole node of a SINGLE layer. */
  private static final Set<String> COMPANION_REQUIRED =
      Set.of(
          RideName.ENCHANTED_TIKI_ROOM.toMatchString(), RideName.RED_CAR_TROLLEY.toMatchString());

  /** AND layers are only allowed when at least one node is one of these rides. */
  private static final Set<String> AND_ANCHORS =
      Set.of(
          RideName.RED_CAR_TROLLEY.toMatchString(),
          RideName.ENCHANTED_TIKI_ROOM.toMatchString(),
          RideName.GREAT_MOMENTS_WITH_MR_LINCOLN.toMatchString());

  private DailyPlanGenerator() {}

  public static DailyPlan generate(LocalDate today) {
    RideCountManager counts = RideCountManager.getInstance();

    DailyPlan plan = new DailyPlan();
    plan.date = today.toString();
    plan.generatedAtEpochMs = System.currentTimeMillis();
    plan.snapshotCounts = new java.util.HashMap<>();
    for (RideName ride : RideName.values()) {
      plan.snapshotCounts.put(ride.toMatchString(), counts.getRideCount(ride));
    }
    plan.layers = new ArrayList<>();

    List<RideName> eligible = buildEligibleRides();
    Random random = new Random();
    appendLayers(plan, eligible, random, INITIAL_LAYER_COUNT);

    // Layer 0 is active from birth — give it a baseline equal to plan generation state.
    if (!plan.layers.isEmpty()) {
      plan.layers.get(0).baselineCounts = new java.util.HashMap<>(plan.snapshotCounts);
    }

    return plan;
  }

  public static List<RideName> buildEligibleRides() {
    boolean onlyAutograbbing = ModConfig.currentSetting.onlyAutograbbing;
    RideCountManager counts = RideCountManager.getInstance();

    List<RideName> eligible = new ArrayList<>();
    for (RideName ride : RideName.sortedByDisplayName()) {
      if (ModConfig.currentSetting.hiddenRides.contains(ride.toMatchString())) {
        continue;
      }
      if (onlyAutograbbing && !AutograbHolder.hasAutograb(ride)) {
        continue;
      }
      if (ride.getRideTime() >= 99999) {
        continue;
      }
      if (counts.getRideCount(ride) >= ModConfig.currentSetting.getMaxGoalForRide(ride)) {
        continue;
      }
      eligible.add(ride);
    }
    return eligible;
  }

  /**
   * Ensures there are at least {@link #MIN_UNFINISHED_TAIL} unfinished layers at the tail of the
   * plan. Returns true if the plan was extended (caller should persist). Cheap no-op when the
   * invariant already holds.
   */
  public static boolean ensureTailCapacity(DailyPlan plan) {
    if (plan == null || plan.layers == null) {
      return false;
    }
    int unfinished = 0;
    for (DailyPlanLayer layer : plan.layers) {
      if (!layer.completed) {
        unfinished++;
      }
    }
    int needed = MIN_UNFINISHED_TAIL - unfinished;
    if (needed <= 0) {
      return false;
    }
    List<RideName> eligible = buildEligibleRides();
    Random random = new Random();
    int before = plan.layers.size();
    appendLayers(plan, eligible, random, needed);
    return plan.layers.size() > before;
  }

  /**
   * Appends up to {@code count} new layers. Skips any the generator cannot construct (e.g. out of
   * eligible rides).
   */
  public static void appendLayers(
      DailyPlan plan, List<RideName> eligible, Random random, int count) {
    if (plan.layers == null) {
      plan.layers = new ArrayList<>();
    }
    for (int i = 0; i < count; i++) {
      DailyPlanLayer layer = nextDailyQuestLayer(plan);
      if (layer == null) {
        layer = generateLayer(plan.layers, eligible, random);
      }
      if (layer == null) {
        break;
      }
      plan.layers.add(layer);
    }
  }

  /**
   * Builds a quest-layer for the next pending daily quest the plan hasn't pinned yet, or null when
   * there is none (no fresh snapshot, all quests already pinned, or the quest's ride no longer
   * resolves). Pre-seeds {@link DailyPlanLayer#baselineCounts} so the activation-time delta lines
   * up with the server-reported observed progress at capture time.
   */
  /**
   * Builds a quest layer for the next eligible-and-unpinned daily quest, or null when none. Public
   * entry point for {@link DailyPlanManager#injectPendingQuestLayers} which pulls quests in
   * immediately on snapshot capture rather than waiting for the tail to drain.
   */
  public static DailyPlanLayer buildNextDailyQuestLayer(DailyPlan plan) {
    return nextDailyQuestLayer(plan);
  }

  private static DailyPlanLayer nextDailyQuestLayer(DailyPlan plan) {
    Optional<DailyQuest> next = DailyQuestState.getInstance().nextEligibleForPlan(plan);
    if (next.isEmpty()) {
      return null;
    }
    DailyQuest quest = next.get();
    DailyQuest.Kind kind = quest.kindOrDefault();

    if (kind == DailyQuest.Kind.RIDE) {
      RideName ride = RideName.fromMatchString(quest.rideMatchName);
      if (ride == RideName.UNKNOWN) {
        return null;
      }
    }

    DailyPlanNode node = new DailyPlanNode(quest.rideMatchName, Math.max(1, quest.target));
    if (kind != DailyQuest.Kind.RIDE) {
      node.displayLabel = quest.displayLabel;
    }
    List<DailyPlanNode> nodes = new ArrayList<>(1);
    nodes.add(node);
    DailyPlanLayer layer = new DailyPlanLayer(LayerType.SINGLE, nodes);
    layer.fromDailyQuest = true;
    layer.questWindowKey = DailyQuestState.currentWindowKey();

    if (kind == DailyQuest.Kind.RIDE) {
      DailyQuestSnapshot snap = DailyQuestState.getInstance().getSnapshot();
      if (snap != null && snap.rideCountsAtCapture != null) {
        Integer atCapture = snap.rideCountsAtCapture.get(quest.rideMatchName);
        if (atCapture != null) {
          Map<String, Integer> baseline = new HashMap<>();
          baseline.put(quest.rideMatchName, atCapture - quest.observedProgress);
          layer.baselineCounts = baseline;
        }
      }
    } else {
      // Special quests don't auto-complete via ride counts. Pre-set an empty baseline so the
      // progress tracker doesn't try to seed one from RideName.UNKNOWN counts on activation.
      layer.baselineCounts = new HashMap<>();
    }
    return layer;
  }

  private static DailyPlanLayer generateLayer(
      List<DailyPlanLayer> existing, List<RideName> eligible, Random random) {
    if (eligible.isEmpty()) {
      return null;
    }

    Set<String> recent = ridesInLastLayers(existing, NO_REPEAT_WINDOW);
    List<RideName> candidates = new ArrayList<>();
    for (RideName ride : eligible) {
      if (!recent.contains(ride.toMatchString())) {
        candidates.add(ride);
      }
    }
    if (candidates.isEmpty()) {
      candidates = new ArrayList<>(eligible);
    }

    LayerType type = pickLayerType(existing, random);
    int wantNodes = nodeCountFor(type, random);

    if (candidates.size() < wantNodes) {
      type = LayerType.SINGLE;
      wantNodes = 1;
    }

    if (type == LayerType.SINGLE) {
      List<RideName> singleCandidates = new ArrayList<>(candidates.size());
      for (RideName ride : candidates) {
        if (!COMPANION_REQUIRED.contains(ride.toMatchString())) {
          singleCandidates.add(ride);
        }
      }
      if (!singleCandidates.isEmpty()) {
        Collections.shuffle(singleCandidates, random);
        RideName pick = singleCandidates.get(0);
        List<DailyPlanNode> nodes = new ArrayList<>(1);
        nodes.add(new DailyPlanNode(pick.toMatchString(), chooseK(pick, random)));
        return new DailyPlanLayer(LayerType.SINGLE, nodes);
      }
      // All candidates are companion-required — bump to an OR pair.
      if (candidates.size() < 2) {
        return null;
      }
      type = LayerType.OR;
      wantNodes = 2;
    }

    Collections.shuffle(candidates, random);
    List<DailyPlanNode> nodes = new ArrayList<>(wantNodes);
    for (int i = 0; i < wantNodes; i++) {
      RideName pick = candidates.get(i);
      nodes.add(new DailyPlanNode(pick.toMatchString(), chooseK(pick, random)));
    }

    if (type == LayerType.AND && !containsAndAnchor(nodes)) {
      type = LayerType.OR;
    }

    return new DailyPlanLayer(type, nodes);
  }

  private static boolean containsAndAnchor(List<DailyPlanNode> nodes) {
    for (DailyPlanNode node : nodes) {
      if (AND_ANCHORS.contains(node.ride)) {
        return true;
      }
    }
    return false;
  }

  /**
   * k by ride duration: &gt;10 min → 1, 5–10 min → 2 or 3, &lt;5 min → 2 through 5. Matches the
   * "don't ask for 5 laps of Pirates" intuition. Lincoln is capped at 1 — it's a slog to grind.
   */
  private static int chooseK(RideName ride, Random random) {
    if (ride == RideName.GREAT_MOMENTS_WITH_MR_LINCOLN) {
      return 1;
    }
    int rideTimeSeconds = ride.getRideTime();
    if (rideTimeSeconds > 600) {
      return 1;
    }
    if (rideTimeSeconds >= 300) {
      return 2 + random.nextInt(2); // 2 or 3
    }
    return 2 + random.nextInt(4); // 2..5
  }

  private static Set<String> ridesInLastLayers(List<DailyPlanLayer> existing, int windowSize) {
    Set<String> out = new HashSet<>();
    int start = Math.max(0, existing.size() - windowSize);
    for (int i = start; i < existing.size(); i++) {
      for (DailyPlanNode node : existing.get(i).nodes) {
        out.add(node.ride);
      }
    }
    return out;
  }

  private static LayerType pickLayerType(List<DailyPlanLayer> existing, Random random) {
    int layerIndex = existing.size();
    if (layerIndex == 0) {
      return LayerType.SINGLE;
    }

    // Never chain two non-SINGLE layers back-to-back. This also keeps the companion-required
    // rides (Tiki, Red Car Trolley) from reappearing, since SINGLE layers exclude them.
    LayerType previous = existing.get(layerIndex - 1).type;
    if (previous == LayerType.AND || previous == LayerType.OR) {
      return LayerType.SINGLE;
    }

    // Every 3rd layer after index 0 is an act-break (AND).
    if (layerIndex % 3 == 0) {
      return LayerType.AND;
    }

    int roll = random.nextInt(100);
    if (roll < 50) {
      return LayerType.SINGLE;
    }
    if (roll < 85) {
      return LayerType.OR;
    }
    return LayerType.AND;
  }

  private static int nodeCountFor(LayerType type, Random random) {
    return switch (type) {
      case SINGLE -> 1;
      case OR -> random.nextBoolean() ? 2 : 3;
      case AND -> 2;
      case TWO_OF_THREE -> 3; // retired — still handled for backward compat with old plans
    };
  }
}
