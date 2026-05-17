package com.chenweikeng.imf.nra.dailyplan;

/**
 * One node in the daily ride plan. Stage 1 is linear-only, so every node is a single ride with a
 * target count k. {@code completed} is reserved for Stage 2 (auto-completion) — always false in
 * Stage 1.
 *
 * <p>Special daily-quest nodes (RIDDLE_RIDE / NPC) carry the quest's pin-key in {@link #ride} (e.g.
 * {@code :riddle:8af3}, {@code :npc:boba_fett}) and a {@link #displayLabel} for the HUD. They never
 * resolve to a {@link com.chenweikeng.imf.nra.ride.RideName} so the progress tracker leaves them
 * for {@link DailyPlanManager#reconcileSpecialQuestLayers} to flip on the next snapshot capture.
 */
public class DailyPlanNode {
  /** The ride's match name (see {@code RideName.toMatchString()}). */
  public String ride;

  /** Target completion count for this node. */
  public int k;

  public boolean completed;

  /**
   * Short HUD label used when the node represents a special daily quest (RIDDLE_RIDE / NPC) — its
   * {@link #ride} is a sentinel and won't resolve to a real {@code RideName}. Null for regular
   * nodes; the renderer falls back to {@code RideName.getShortName()} in that case.
   */
  public String displayLabel;

  public DailyPlanNode() {}

  public DailyPlanNode(String ride, int k) {
    this.ride = ride;
    this.k = k;
    this.completed = false;
  }
}
