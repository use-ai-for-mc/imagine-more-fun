package com.chenweikeng.imf.nra.dailyplan;

/**
 * One parsed daily quest read off the server's Daily Objectives chest GUI.
 *
 * <p>Five {@link Kind kinds} are recognised:
 *
 * <ul>
 *   <li>{@link Kind#RIDE} — "Goal: Ride X N times" / "Goal: Watch X". {@link #rideMatchName} is the
 *       canonical ride match-name and the layer auto-completes via ride counts.
 *   <li>{@link Kind#LAND_RIDE} — "Goal: Ride any ride in &lt;land&gt; N times". Pinned by a
 *       sentinel; completes via snapshot reconcile.
 *   <li>{@link Kind#RIDDLE_RIDE} — "Goal: Find and ride the correct ride". The target ride is
 *       hidden in flavour text; the layer is rendered as a "?" block and only completes when the
 *       refreshed snapshot reports it done (or gone).
 *   <li>{@link Kind#NPC} — "Goal: Help &lt;NPC&gt; ...". Non-ride bounty / fetch quests; same
 *       snapshot-driven completion as riddles.
 *   <li>{@link Kind#TASK} — catch-all for any other "Goal: ..." shape (e.g. "Collect and save the
 *       loose Porgs"). Same snapshot-driven completion as NPC; ensures the parser never silently
 *       drops a quest just because its wording is novel.
 * </ul>
 *
 * <p>For non-RIDE kinds, {@link #rideMatchName} holds a synthetic sentinel ({@code :land:...},
 * {@code :riddle:...}, {@code :npc:...}, {@code :task:...}) so the existing window-scoped dedup
 * keys keep working. Stored in {@link DailyQuestSnapshot} which is persisted to disk so the plan
 * can keep injecting quest layers across restarts.
 */
public class DailyQuest {
  /** Discriminates the goal shapes the parser recognises. Persisted as the enum name. */
  public enum Kind {
    RIDE,
    RIDDLE_RIDE,
    NPC,
    LAND_RIDE,
    TASK
  }

  /** Default RIDE for backward compat: legacy snapshots have no {@code kind} field. */
  public Kind kind = Kind.RIDE;

  /**
   * For RIDE: the canonical ride match name used by {@link
   * com.chenweikeng.imf.nra.ride.RideName#fromMatchString}. For RIDDLE_RIDE / NPC: a synthetic
   * sentinel that uniquely identifies the quest within an 8h window — must be non-null so the same
   * dedup path treats it as "pinned".
   */
  public String rideMatchName;

  /** Total laps the quest asks for. RIDDLE_RIDE / NPC / "Watch X" all use 1. */
  public int target;

  /** What the server reported as already done at capture time (the "X" in "X / target"). */
  public int observedProgress;

  /** Reward in Kingdom Coins, parsed from "Reward: N Kingdom Coins". */
  public int rewardCoins;

  /**
   * Short HUD label shown inside the special-quest node box. {@code null} for RIDE quests (the
   * renderer falls back to {@code RideName.getShortName()}). Examples: "RIDDLE", "BOBA FETT".
   */
  public String displayLabel;

  public DailyQuest() {}

  public DailyQuest(String rideMatchName, int target, int observedProgress, int rewardCoins) {
    this.rideMatchName = rideMatchName;
    this.target = target;
    this.observedProgress = observedProgress;
    this.rewardCoins = rewardCoins;
  }

  /** GSON deserialisation can leave {@link #kind} null on legacy snapshots — treat that as RIDE. */
  public Kind kindOrDefault() {
    return kind == null ? Kind.RIDE : kind;
  }
}
