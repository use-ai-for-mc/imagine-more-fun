package com.chenweikeng.imf.nra.dailyplan;

import com.chenweikeng.imf.nra.ride.RideName;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls a {@link DailyQuest} out of a quest-info item's lore lines. Recognises five goal shapes:
 *
 * <ul>
 *   <li>{@code Goal: Ride <ride name> <N> times} → {@link DailyQuest.Kind#RIDE}, target = N
 *   <li>{@code Goal: Watch <ride name>} → {@link DailyQuest.Kind#RIDE}, target = 1
 *   <li>{@code Goal: Ride any ride in <land> <N> times} → {@link DailyQuest.Kind#LAND_RIDE}, target
 *       = N
 *   <li>{@code Goal: Find and ride the correct ride} → {@link DailyQuest.Kind#RIDDLE_RIDE}, target
 *       = 1; the riddle prose (the lines above the goal) becomes the sentinel hash so the same
 *       riddle dedups across re-captures
 *   <li>{@code Goal: Help <NPC> ...} → {@link DailyQuest.Kind#NPC}, target = 1; the leading
 *       capitalised words after "Help " are taken as the NPC name for the HUD label
 * </ul>
 *
 * <p>Any other {@code Goal: ...} wording falls through to {@link DailyQuest.Kind#TASK} with a
 * goal-text hash sentinel and the trailing capitalised word as the HUD label — so a novel quest
 * shape (e.g. "Collect and save the loose Porgs") still pins as a gold ★ DAILY layer rather than
 * being silently dropped.
 *
 * <p>Lines often wrap mid-name, so the goal block is reconstructed by joining consecutive non-blank
 * lines until the parser hits "Objective Progress:", "Reward:", or "Near:".
 */
public final class DailyQuestParser {

  private static final Pattern LAND_RIDE_PATTERN =
      Pattern.compile("^Goal: Ride any ride in (.+) (\\d+) times?$");
  private static final Pattern RIDE_PATTERN = Pattern.compile("^Goal: Ride (.+) (\\d+) times?$");
  private static final Pattern WATCH_PATTERN = Pattern.compile("^Goal: Watch (.+)$");
  private static final Pattern RIDDLE_PATTERN =
      Pattern.compile("^Goal: Find and ride the correct ride$");
  private static final Pattern HELP_PATTERN = Pattern.compile("^Goal: Help (.+)$");
  private static final Pattern PROGRESS_PATTERN =
      Pattern.compile("^Objective Progress: (\\d+) / (\\d+)$");
  private static final Pattern REWARD_PATTERN = Pattern.compile("^Reward: (\\d+) Kingdom Coins?$");
  private static final Pattern NEAR_PATTERN = Pattern.compile("^Near: (.+)$");
  private static final Pattern CTA_PATTERN = Pattern.compile("^Click to start this quest!$");

  private DailyQuestParser() {}

  /** Returns the parsed quest if the lore matches one of the supported goal shapes. */
  public static Optional<DailyQuest> parse(List<String> loreLines) {
    if (loreLines == null || loreLines.isEmpty()) {
      return Optional.empty();
    }

    StringBuilder narrativeBuf = new StringBuilder();
    StringBuilder goalBuf = new StringBuilder();
    int observedProgress = -1;
    int observedTarget = -1;
    int rewardCoins = 0;
    boolean inGoal = false;
    boolean afterGoal = false;

    for (String raw : loreLines) {
      String line = raw == null ? "" : raw.trim();
      if (line.isEmpty()) {
        continue;
      }
      Matcher progress = PROGRESS_PATTERN.matcher(line);
      if (progress.matches()) {
        observedProgress = Integer.parseInt(progress.group(1));
        observedTarget = Integer.parseInt(progress.group(2));
        inGoal = false;
        afterGoal = true;
        continue;
      }
      Matcher reward = REWARD_PATTERN.matcher(line);
      if (reward.matches()) {
        rewardCoins = Integer.parseInt(reward.group(1));
        inGoal = false;
        afterGoal = true;
        continue;
      }
      if (NEAR_PATTERN.matcher(line).matches() || CTA_PATTERN.matcher(line).matches()) {
        // "Near: X" / "Click to start this quest!" terminate the wrapped goal block but carry no
        // info we keep — locations and CTAs are server-driven flavour.
        inGoal = false;
        afterGoal = true;
        continue;
      }
      if (line.startsWith("Goal:")) {
        inGoal = true;
        afterGoal = false;
        if (goalBuf.length() > 0) {
          goalBuf.append(' ');
        }
        goalBuf.append(line);
        continue;
      }
      if (inGoal) {
        goalBuf.append(' ').append(line);
        continue;
      }
      if (!afterGoal) {
        if (narrativeBuf.length() > 0) {
          narrativeBuf.append(' ');
        }
        narrativeBuf.append(line);
      }
    }

    String goal = goalBuf.toString();
    if (goal.isEmpty()) {
      return Optional.empty();
    }

    Matcher landRide = LAND_RIDE_PATTERN.matcher(goal);
    Matcher ride = RIDE_PATTERN.matcher(goal);
    Matcher watch = WATCH_PATTERN.matcher(goal);
    Matcher riddle = RIDDLE_PATTERN.matcher(goal);
    Matcher help = HELP_PATTERN.matcher(goal);

    // Land challenges must be checked before RIDE_PATTERN, since "Ride any ride in X N times" also
    // matches RIDE_PATTERN with rideName="any ride in X" — that path would just bail on UNKNOWN.
    if (landRide.matches()) {
      String land = landRide.group(1).trim();
      int target = Integer.parseInt(landRide.group(2));
      DailyQuest q = new DailyQuest();
      q.kind = DailyQuest.Kind.LAND_RIDE;
      q.rideMatchName = ":land:" + land.toLowerCase(Locale.ENGLISH).replaceAll("\\s+", "_");
      q.target = target;
      q.observedProgress = observedProgress < 0 ? 0 : Math.min(observedProgress, target);
      q.rewardCoins = rewardCoins;
      q.displayLabel = land.toUpperCase(Locale.ENGLISH);
      return Optional.of(q);
    }

    if (ride.matches() || watch.matches()) {
      String rideName;
      int target;
      if (ride.matches()) {
        rideName = ride.group(1).trim();
        target = Integer.parseInt(ride.group(2));
      } else {
        rideName = watch.group(1).trim();
        target = 1;
      }
      // Goal text uses the same shortened ride names as the sidebar ("Rise of the Resistance"
      // rather than the canonical "Star Wars: Rise of the Resistance"). fromTruncatedString
      // already handles the known overrides (ROTR, Tower of Terror, Astro Orbiter, ...). When it
      // resolves, normalise to the canonical match-string so the layer's node key matches the
      // RideCountManager and the layer auto-completes via ride counts.
      RideName resolved = RideName.fromTruncatedString(rideName);
      if (resolved != RideName.UNKNOWN) {
        int progress = observedProgress < 0 ? 0 : Math.min(observedProgress, target);
        return Optional.of(new DailyQuest(resolved.toMatchString(), target, progress, rewardCoins));
      }
      // Fall through to the TASK catch-all: a ride name we can't resolve still pins as a special
      // layer with snapshot-driven completion, rather than being silently dropped.
    }

    if (riddle.matches()) {
      String narrative = narrativeBuf.toString();
      DailyQuest q = new DailyQuest();
      q.kind = DailyQuest.Kind.RIDDLE_RIDE;
      q.rideMatchName = ":riddle:" + Integer.toHexString(narrative.hashCode());
      q.target = 1;
      q.observedProgress = observedProgress < 0 ? 0 : Math.min(observedProgress, 1);
      q.rewardCoins = rewardCoins;
      q.displayLabel = "RIDDLE";
      return Optional.of(q);
    }

    if (help.matches()) {
      String helpTarget = help.group(1).trim();
      String npcName = extractNpcName(helpTarget);
      DailyQuest q = new DailyQuest();
      q.kind = DailyQuest.Kind.NPC;
      q.rideMatchName = ":npc:" + npcName.toLowerCase(Locale.ENGLISH).replaceAll("\\s+", "_");
      q.target = 1;
      q.observedProgress = observedProgress < 0 ? 0 : Math.min(observedProgress, 1);
      q.rewardCoins = rewardCoins;
      q.displayLabel = npcName.toUpperCase(Locale.ENGLISH);
      return Optional.of(q);
    }

    // Catch-all: any other "Goal: ..." wording becomes a TASK so the layer still pins.
    if (goal.startsWith("Goal: ")) {
      String goalText = goal.substring("Goal: ".length()).trim();
      if (goalText.isEmpty()) {
        return Optional.empty();
      }
      int target = observedTarget > 0 ? observedTarget : 1;
      DailyQuest q = new DailyQuest();
      q.kind = DailyQuest.Kind.TASK;
      q.rideMatchName = ":task:" + Integer.toHexString(goalText.hashCode());
      q.target = target;
      q.observedProgress = observedProgress < 0 ? 0 : Math.min(observedProgress, target);
      q.rewardCoins = rewardCoins;
      q.displayLabel = extractTaskLabel(goalText);
      return Optional.of(q);
    }

    return Optional.empty();
  }

  /**
   * Picks a short HUD label out of a free-form goal text by walking the words right-to-left and
   * returning the trailing capitalised word ("Collect and save the loose Porgs" → "PORGS"). Falls
   * back to the last alphabetic word, then to "TASK", so the label is never empty.
   */
  private static String extractTaskLabel(String goalText) {
    String[] words = goalText.split("\\s+");
    String fallback = null;
    for (int i = words.length - 1; i >= 0; i--) {
      String w = words[i].replaceAll("[^A-Za-z]", "");
      if (w.isEmpty()) {
        continue;
      }
      if (Character.isUpperCase(w.charAt(0))) {
        return w.toUpperCase(Locale.ENGLISH);
      }
      if (fallback == null) {
        fallback = w.toUpperCase(Locale.ENGLISH);
      }
    }
    return fallback != null ? fallback : "TASK";
  }

  /**
   * Pulls the NPC name out of a "Help &lt;NPC&gt; &lt;verb&gt; ..." string by taking the leading
   * run of capitalised words ("Boba Fett track down a fugitive" → "Boba Fett"). Falls back to the
   * full string when nothing is capitalised so we never end up with an empty label.
   */
  private static String extractNpcName(String helpTarget) {
    String[] words = helpTarget.split("\\s+");
    StringBuilder name = new StringBuilder();
    for (String w : words) {
      if (w.isEmpty()) {
        continue;
      }
      char c = w.charAt(0);
      if (!Character.isUpperCase(c)) {
        break;
      }
      if (name.length() > 0) {
        name.append(' ');
      }
      name.append(w);
    }
    return name.length() > 0 ? name.toString() : helpTarget;
  }
}
