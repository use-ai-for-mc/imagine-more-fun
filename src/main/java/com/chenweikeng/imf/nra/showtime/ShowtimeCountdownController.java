package com.chenweikeng.imf.nra.showtime;

import com.chenweikeng.imf.nra.ServerState;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/** Tracks a locally rendered show countdown calibrated from ImagineFun's action bar. */
public final class ShowtimeCountdownController {
  private static final Pattern COUNTDOWN_PATTERN =
      Pattern.compile("^The next show will begin in (?:(\\d+)h\\s*)?(?:(\\d+)m\\s*)?(?:(\\d+)s)?$");

  /** Keep the starting reminder visible briefly after the countdown reaches zero. */
  private static final long STARTING_REMINDER_MS = 15_000L;

  private static final ShowtimeCountdownController INSTANCE = new ShowtimeCountdownController();

  private ShowtimeAttraction attraction;
  private ShowtimeAttraction zeroLatchedAttraction;
  private long deadlineEpochMs;

  private ShowtimeCountdownController() {}

  public static ShowtimeCountdownController getInstance() {
    return INSTANCE;
  }

  /** Receives every server action-bar component from the shared GUI mixin. */
  public void onActionBar(Component component) {
    if (component == null || !ServerState.isImagineFunServer()) {
      return;
    }

    Minecraft client = Minecraft.getInstance();
    LocalPlayer player = client.player;
    if (player == null || client.level == null) {
      return;
    }

    Matcher matcher = COUNTDOWN_PATTERN.matcher(component.getString().trim());
    if (!matcher.matches()) {
      return;
    }

    ShowtimeAttraction nearbyAttraction = ShowtimeAttraction.findNear(player);
    if (nearbyAttraction == null) {
      return;
    }

    long remainingSeconds = parseSeconds(matcher);
    if (remainingSeconds < 0) {
      return;
    }

    if (remainingSeconds == 0L) {
      if (nearbyAttraction == zeroLatchedAttraction) {
        return;
      }
      zeroLatchedAttraction = nearbyAttraction;
    } else {
      zeroLatchedAttraction = null;
    }

    try {
      attraction = nearbyAttraction;
      deadlineEpochMs =
          Math.addExact(System.currentTimeMillis(), Math.multiplyExact(remainingSeconds, 1_000L));
    } catch (ArithmeticException exception) {
      clearCountdown();
    }
  }

  /** Returns the current render state, clearing it after the starting reminder expires. */
  public CountdownSnapshot getSnapshot(long nowEpochMs) {
    if (attraction == null) {
      return null;
    }

    long remainingMs = deadlineEpochMs - nowEpochMs;
    if (remainingMs > 0) {
      long remainingSeconds = (remainingMs + 999L) / 1_000L;
      return new CountdownSnapshot(attraction.displayName, remainingSeconds, false);
    }

    if (remainingMs >= -STARTING_REMINDER_MS) {
      return new CountdownSnapshot(attraction.displayName, 0L, true);
    }

    clearCountdown();
    return null;
  }

  public void reset() {
    clearCountdown();
    zeroLatchedAttraction = null;
  }

  private void clearCountdown() {
    attraction = null;
    deadlineEpochMs = 0L;
  }

  private static long parseSeconds(Matcher matcher) {
    String hoursGroup = matcher.group(1);
    String minutesGroup = matcher.group(2);
    String secondsGroup = matcher.group(3);
    if (hoursGroup == null && minutesGroup == null && secondsGroup == null) {
      return -1L;
    }

    try {
      long hours = hoursGroup == null ? 0L : Long.parseLong(hoursGroup);
      long minutes = minutesGroup == null ? 0L : Long.parseLong(minutesGroup);
      long seconds = secondsGroup == null ? 0L : Long.parseLong(secondsGroup);
      return Math.addExact(
          Math.addExact(Math.multiplyExact(hours, 3_600L), Math.multiplyExact(minutes, 60L)),
          seconds);
    } catch (ArithmeticException | NumberFormatException exception) {
      return -1L;
    }
  }

  public record CountdownSnapshot(
      String attractionName, long remainingSeconds, boolean startingNow) {}

  /**
   * Initial generous anchors derived from live samples inside each waiting circle. The server only
   * emits the matching action bar inside a circle, so these bounds identify the attraction rather
   * than trying to reproduce the server's exact region. Replace them with surveyed bounds later.
   */
  private enum ShowtimeAttraction {
    TIKI_ROOM("Tiki Room", 117.546, 64.0, 330.369),
    MR_LINCOLN("Mr. Lincoln", -119.360, 64.0, 0.932);

    private static final String DIMENSION_PATH = "dlnew";
    private static final double MAX_HORIZONTAL_DISTANCE_SQUARED = 24.0 * 24.0;
    private static final double MAX_VERTICAL_DISTANCE = 12.0;

    private final String displayName;
    private final double x;
    private final double y;
    private final double z;

    ShowtimeAttraction(String displayName, double x, double y, double z) {
      this.displayName = displayName;
      this.x = x;
      this.y = y;
      this.z = z;
    }

    private static ShowtimeAttraction findNear(LocalPlayer player) {
      if (!player.level().dimension().identifier().getPath().equals(DIMENSION_PATH)) {
        return null;
      }

      ShowtimeAttraction nearest = null;
      double nearestDistanceSquared = Double.MAX_VALUE;
      for (ShowtimeAttraction candidate : values()) {
        if (Math.abs(player.getY() - candidate.y) > MAX_VERTICAL_DISTANCE) {
          continue;
        }

        double dx = player.getX() - candidate.x;
        double dz = player.getZ() - candidate.z;
        double distanceSquared = dx * dx + dz * dz;
        if (distanceSquared <= MAX_HORIZONTAL_DISTANCE_SQUARED
            && distanceSquared < nearestDistanceSquared) {
          nearest = candidate;
          nearestDistanceSquared = distanceSquared;
        }
      }
      return nearest;
    }
  }
}
