package com.chenweikeng.imf.nra.config;

import com.chenweikeng.imf.ImfChat;
import com.chenweikeng.imf.ImfStorage;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * First-launch nudge toward the Ride Plan HUD. The first time this build runs, the ride hub is
 * forced to {@link RideHubMode#RIDE_PLAN} even if the rider had previously picked Strategy Hub.
 * When that override actually replaced a Strategy Hub choice, a one-time chat message on the next
 * world join explains how to switch back. A marker file under the config dir makes it run once.
 */
public final class RidePlanNudge {

  // Set by applyFirstLaunch() only when it actually overrode a Strategy Hub choice; read once by
  // showMessageIfPending() on the next world join.
  private static volatile boolean pendingMessage;

  private RidePlanNudge() {}

  /**
   * Force the ride hub to Ride Plan on the first launch. Call once, after {@link ModConfig#load}.
   */
  public static void applyFirstLaunch() {
    Path marker = ImfStorage.ridePlanNudgeMarker();
    if (Files.exists(marker)) {
      return;
    }
    if (ModConfig.currentSetting.rideHubMode != RideHubMode.RIDE_PLAN) {
      ModConfig.currentSetting.rideHubMode = RideHubMode.RIDE_PLAN;
      ModConfig.save();
      pendingMessage = true;
    }
    try {
      Files.writeString(marker, "ride-plan first-launch nudge applied\n");
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.warn("[RidePlanNudge] could not write marker {}", marker, e);
    }
  }

  /** Show the one-time switch-back message if the first launch overrode a Strategy Hub choice. */
  public static void showMessageIfPending() {
    if (!pendingMessage) {
      return;
    }
    pendingMessage = false;
    ImfChat.send(
        "§6Ride Plan§e is a new feature, and it's now your ride HUD."
            + " We'd love for you to test it!");
    ImfChat.sendRaw(
        "§7  Ride Plan lays out today's plan as a tree across the top of your screen,"
            + " in place of the per-ride Strategy Hub.");
    ImfChat.sendRaw(
        "§7  Prefer the classic view? Run §f/imf §7→ click §fEdit §7on the Current Settings"
            + " row §7→ open the §fTracker §7tab §7→ set §fRide hub §7to §fStrategy Hub§7.");
  }
}
