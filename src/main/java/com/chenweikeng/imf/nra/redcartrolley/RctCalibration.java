package com.chenweikeng.imf.nra.redcartrolley;

import com.chenweikeng.imf.ImfChat;
import com.chenweikeng.imf.mixin.NraPlayerTabOverlayAccessor;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.ServerState;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.chenweikeng.imf.nra.ride.RideName;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;

/**
 * Records the wall-clock instant each ride <b>departs GotG</b> — the phase-0 reference the web
 * predictor (https://use-ai-for-mc.github.io/rct/) calibrates from.
 *
 * <p>The scoreboard ride clock ({@link CurrentRideHolder#getElapsedSeconds()}) is whole-second, but
 * the moment it reads {@code N} the train has moved for exactly {@code N} seconds, so {@code now −
 * N·1000} reconstructs the departure time; the minimum over the leg lands within a client tick
 * (~50&nbsp;ms).
 *
 * <p>Always on (no config); silent — it logs each departure to the client log and keeps the times
 * <b>in memory only</b> for the current session (a game restart clears them; nothing is written to
 * disk). {@code /imf rct} prints the numbers to type into the web app: the latest departure as the
 * <b>anchor</b> (full Unix seconds), and — once two departures span enough time — a <b>timed</b>
 * period. It does not fit or snap a period; a short baseline is less accurate than two far-apart
 * departure times.
 */
public final class RctCalibration {
  private static final RctCalibration INSTANCE = new RctCalibration();

  /** Only treat a leg as a GotG departure if caught this early into it (seconds of ride clock). */
  private static final int NEW_LEG_MAX_ELAPSED = 8;

  /** Log the departure once the ride clock reaches this (a few ticks have refined the estimate). */
  private static final int LOG_AT_ELAPSED = 4;

  /** Player must be within this horizontal distance of the GotG terminus to classify the leg. */
  private static final double GOTG_RADIUS = 80.0;

  private final List<Double> departuresSec = new ArrayList<>(); // this session, in memory only
  private String lastGroup;

  // Per-leg detection state.
  private boolean prevRiding;
  private int prevElapsed = -1;
  private boolean trackingGotg;
  private boolean loggedThisLeg;
  private long pendingDepartureMs;

  private RctCalibration() {}

  public static RctCalibration getInstance() {
    return INSTANCE;
  }

  /** Clears the in-flight leg state and this session's departures (disconnect / world change). */
  public void reset() {
    departuresSec.clear();
    lastGroup = null;
    prevRiding = false;
    prevElapsed = -1;
    trackingGotg = false;
    loggedThisLeg = false;
  }

  public void tick(Minecraft client) {
    RctRoute route = RctRoute.get();
    if (!ServerState.isImagineFunServer()
        || route == null
        || client.player == null
        || client.level == null) {
      prevRiding = false;
      trackingGotg = false;
      return;
    }

    // A move to an unsynced instance (e.g. disneyland → dvc) can shift the cycle — start fresh.
    String group = currentInstanceGroup(client);
    if (group != null && lastGroup != null && !group.equals(lastGroup)) {
      departuresSec.clear();
      trackingGotg = false;
    }
    if (group != null) {
      lastGroup = group;
    }

    boolean riding =
        CurrentRideHolder.getCurrentRide() == RideName.RED_CAR_TROLLEY
            && CurrentRideHolder.getElapsedSeconds() != null;
    if (!riding) {
      prevRiding = false;
      prevElapsed = -1;
      trackingGotg = false;
      return;
    }

    int elapsed = CurrentRideHolder.getElapsedSeconds();
    long now = System.currentTimeMillis();

    // A fresh leg = the ride clock just (re)started: resumed from a dwell, or reset within riding.
    boolean newLeg = !prevRiding || elapsed + 2 < prevElapsed;
    if (newLeg) {
      trackingGotg = elapsed <= NEW_LEG_MAX_ELAPSED && nearGotg(route, client);
      loggedThisLeg = false;
      pendingDepartureMs = now - elapsed * 1000L;
    }

    if (trackingGotg && !loggedThisLeg) {
      pendingDepartureMs = Math.min(pendingDepartureMs, now - elapsed * 1000L);
      if (elapsed >= LOG_AT_ELAPSED) {
        logDeparture(pendingDepartureMs);
        loggedThisLeg = true;
      }
    }

    prevRiding = true;
    prevElapsed = elapsed;
  }

  private boolean nearGotg(RctRoute route, Minecraft client) {
    double[] gotg = route.posAt(0);
    double[] bv = route.posAt(route.length());
    double px = client.player.getX();
    double pz = client.player.getZ();
    double dG = sq(px - gotg[0]) + sq(pz - gotg[2]);
    double dB = sq(px - bv[0]) + sq(pz - bv[2]);
    return dG < dB && dG < GOTG_RADIUS * GOTG_RADIUS;
  }

  /** Silent — keep the time in memory and note it in the client log. No chat, no file. */
  private void logDeparture(long departureMs) {
    departuresSec.add(departureMs / 1000.0);
    NotRidingAlertClient.LOGGER.info(
        "RCT GotG departure: {}s Unix (#{} this session)",
        String.format("%.1f", departureMs / 1000.0),
        departuresSec.size());
  }

  /** {@code /imf rct}: the numbers to paste into the web predictor — anchor + (timed) period. */
  public void status() {
    if (!ServerState.isImagineFunServer()) {
      ImfChat.sendWarn("RCT calibration only runs on ImagineFun.");
      return;
    }
    int n = departuresSec.size();
    if (n == 0) {
      ImfChat.send(
          ImfChat.YELLOW
              + "[RCT] "
              + ImfChat.WHITE
              + "No GotG departures logged yet this session — ride the trolley from GotG, then"
              + " /imf rct.");
      return;
    }

    double anchor = departuresSec.get(n - 1);
    ImfChat.send(
        ImfChat.YELLOW
            + "[RCT] "
            + ImfChat.WHITE
            + "Anchor: "
            + ImfChat.GREEN
            + String.format("%.1f", anchor)
            + "§7 (Unix s, GotG departure — paste into the web app's anchor field; "
            + n
            + " logged this session)");

    RctRoute route = RctRoute.get();
    if (n >= 2 && route != null) {
      double span = anchor - departuresSec.get(0);
      long cycles = Math.max(1, Math.round(span / route.cycleSeconds()));
      double period = span / cycles;
      ImfChat.send(
          "§7Period (timed over "
              + fmtSpan(span)
              + " / "
              + cycles
              + " cycles): "
              + ImfChat.WHITE
              + String.format("%.2f", period)
              + "s§7 — a wider span is more accurate.");
    } else {
      ImfChat.send(
          "§7Period: ride from GotG again (ideally 30+ min apart) for a timed reading; the web"
              + " app's built-in period is fine meanwhile.");
    }
  }

  private static String fmtSpan(double sec) {
    long s = Math.round(sec);
    long h = s / 3600;
    long m = (s % 3600) / 60;
    return h > 0 ? h + "h" + m + "m" : m + "m";
  }

  private static String currentInstanceGroup(Minecraft client) {
    String name = currentInstanceName(client);
    return name == null ? null : name.replaceAll("\\d+$", "");
  }

  private static String currentInstanceName(Minecraft client) {
    if (client.gui == null) {
      return null;
    }
    PlayerTabOverlay tab = client.gui.getTabList();
    if (tab == null) {
      return null;
    }
    Component footer = ((NraPlayerTabOverlayAccessor) tab).getFooter();
    if (footer == null) {
      return null;
    }
    for (String line : footer.getString().split("\n")) {
      String t = line.trim();
      if (!t.isEmpty() && !t.contains("players")) {
        return t;
      }
    }
    return null;
  }

  private static double sq(double v) {
    return v * v;
  }
}
