package com.chenweikeng.imf.nra.coaster;

import com.chenweikeng.imf.nra.ServerState;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.chenweikeng.imf.nra.ride.RideName;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Per-ride camera-roll banking, applied <em>on top of</em> any roll the server / SmoothCoasters
 * already does. Each tick, while connected to ImagineFun and the current ride has a registered
 * banking entry whose toggle is on, the rider's position is matched against that ride's baked track
 * ({@link CoasterTrackData}), and the sample's roll — scaled by the ride's strength slider —
 * becomes the camera-roll target. EMA-smoothed so the horizon eases into and out of the addition;
 * eases back to 0 when the gate flips off.
 *
 * <p>{@code NraCameraRollMixin} reads {@link #getCurrentRoll()} on the render thread and rolls the
 * view about its forward axis. The mixin is ride-agnostic — it just consumes the single smoothed
 * roll value. It doesn't know or care whether the server is already tilting the camera; our roll
 * stacks on top, so this is surfaced as "Additional Tilt" in the config UI.
 *
 * <p>Currently only Space Mountain / Hyperspace Mountain are registered. The other ImagineFun
 * coasters had IMF banking ripped out 2026-05-21 once SmoothCoasters' built-in tilt was found to
 * cover them — the SM entries stayed because the user wants the amplified lean.
 */
public final class CoasterCameraBank {
  // EMA smoothing factor per client tick (20 TPS). ~0.2 → a ~0.25 s ease into/out of a bank.
  // Lower = smoother but laggier.
  private static final float SMOOTH_ALPHA = 0.2f;

  private static volatile float currentRoll = 0f;
  private static volatile int lastSample = -1;
  private static volatile RideName lastRide = null;

  /** Binds a ride's banking toggle and its strength slider so they're looked up together. */
  private record CoasterEntry(BooleanSupplier bankingOn, IntSupplier strengthPercent) {}

  private static final Map<RideName, CoasterEntry> ENTRIES = new EnumMap<>(RideName.class);

  static {
    // Space Mountain / Hyperspace Mountain — banking gated by both the SM master toggle and the
    // SM-specific banking sub-toggle, sharing one set of config fields.
    CoasterEntry spaceMountain =
        new CoasterEntry(
            () ->
                ModConfig.currentSetting.spaceMountainEnhancements
                    && ModConfig.currentSetting.spaceMountainBanking,
            () -> ModConfig.currentSetting.spaceMountainBankStrength);
    ENTRIES.put(RideName.SPACE_MOUNTAIN, spaceMountain);
    ENTRIES.put(RideName.HYPERSPACE_MOUNTAIN, spaceMountain);
  }

  private CoasterCameraBank() {}

  public static void register() {
    ClientTickEvents.END_CLIENT_TICK.register(CoasterCameraBank::onClientTick);
  }

  private static void onClientTick(Minecraft mc) {
    float target = 0f;
    RideName ride = null;
    int idx = -1;
    if (mc.player != null && mc.level != null && ServerState.isImagineFunServer()) {
      ride = CurrentRideHolder.getCurrentRide();
      CoasterEntry entry = ride != null ? ENTRIES.get(ride) : null;
      if (entry != null && entry.bankingOn().getAsBoolean()) {
        CoasterTrackData.TrackSamples track = CoasterTrackData.forRide(ride);
        if (track.count > 0) {
          // The vehicle (armor stand) is the entity the track was recorded from, where present.
          Entity ref = mc.player.getVehicle() != null ? mc.player.getVehicle() : mc.player;
          idx = track.nearestSample(ref.getX(), ref.getY(), ref.getZ());
          if (idx >= 0) {
            float strength = entry.strengthPercent().getAsInt() / 100f;
            target = track.roll[idx] * strength;
          }
        }
      }
    }
    lastRide = ride;
    lastSample = idx;
    // EMA toward the target (or toward 0 when inactive) so the horizon eases, never snaps.
    currentRoll += (target - currentRoll) * SMOOTH_ALPHA;
  }

  /** Smoothed camera-roll angle in degrees (signed). Read by the camera-roll mixin per frame. */
  public static float getCurrentRoll() {
    return currentRoll;
  }

  /** DebugBridge health check: {@code java.import("...CoasterCameraBank"):describe()}. */
  public static String describe() {
    RideName ride = lastRide;
    int trackCount = ride != null ? CoasterTrackData.forRide(ride).count : 0;
    boolean bankingOn = false;
    int strength = 0;
    if (ride != null) {
      CoasterEntry entry = ENTRIES.get(ride);
      if (entry != null) {
        bankingOn = entry.bankingOn().getAsBoolean();
        strength = entry.strengthPercent().getAsInt();
      }
    }
    return String.format(
        "ride=%s banking=%s strength=%d%% currentRoll=%.1f deg sample=%d/%d",
        ride, bankingOn, strength, currentRoll, lastSample, trackCount);
  }
}
