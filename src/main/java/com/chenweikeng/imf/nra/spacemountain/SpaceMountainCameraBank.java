package com.chenweikeng.imf.nra.spacemountain;

import com.chenweikeng.imf.nra.config.ModConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Camera-roll banking for Space/Hyperspace Mountain. Each client tick, while the ride is active and
 * the banking toggle is on, the rider's position is matched to the nearest baked track sample and
 * that sample's signed roll (bank) angle — scaled by the configured strength — becomes the
 * camera-roll target. The value is EMA-smoothed so the horizon eases into and out of banked turns
 * rather than snapping.
 *
 * <p>{@code NraCameraRollMixin} reads {@link #getCurrentRoll()} on the render thread and rolls the
 * view about its forward axis. When the ride gate flips off the roll eases back to level.
 *
 * <p>The baked roll comes from {@link SpaceMountainTrackData} (v2 {@code dome_track.bin}); the
 * track geometry is banked by the same per-sample value in {@link SpaceMountainTrackRenderer}, so
 * at strength 100 the rails stay visually "under" the rider. Lower strength keeps the banked rails
 * but stabilises the horizon — the comfort knob, live-editable from the config screen.
 */
public final class SpaceMountainCameraBank {
  // EMA smoothing factor per client tick (20 TPS). ~0.2 -> a ~0.25 s ease into/out of a bank.
  // Lower = smoother but laggier.
  private static final float SMOOTH_ALPHA = 0.2f;

  // Current smoothed roll in degrees; written each tick, read by the mixin on the render thread.
  private static volatile float currentRoll = 0f;

  // Last matched track sample (-1 = none) — diagnostics only.
  private static volatile int lastSample = -1;

  private SpaceMountainCameraBank() {}

  public static void register() {
    ClientTickEvents.END_CLIENT_TICK.register(SpaceMountainCameraBank::onClientTick);
  }

  private static void onClientTick(Minecraft mc) {
    float target = 0f;
    boolean on =
        mc.player != null
            && mc.level != null
            && SpaceMountainOverride.isActive()
            && ModConfig.currentSetting.spaceMountainBanking;
    if (on) {
      // Match against the vehicle the rider sits on (an armor stand on the ImagineFun ride),
      // mirroring SpaceMountainRideAudio — that is the entity the track was recorded from.
      Entity ref = mc.player.getVehicle() != null ? mc.player.getVehicle() : mc.player;
      int idx = SpaceMountainTrackData.nearestSample(ref.getX(), ref.getY(), ref.getZ());
      lastSample = idx;
      if (idx >= 0) {
        float strength = ModConfig.currentSetting.spaceMountainBankStrength / 100f;
        target = SpaceMountainTrackData.roll[idx] * strength;
      }
    } else {
      lastSample = -1;
    }
    // EMA toward the target (or toward 0 when inactive) so the horizon eases, never snaps.
    currentRoll += (target - currentRoll) * SMOOTH_ALPHA;
  }

  /** Smoothed camera-roll angle in degrees (signed). Read by the camera-roll mixin per frame. */
  public static float getCurrentRoll() {
    return currentRoll;
  }

  /** Debug-bridge health check: {@code java.import(...):describe()}. */
  public static String describe() {
    return String.format(
        "banking=%s strength=%d%% currentRoll=%.1f deg sample=%d/%d",
        ModConfig.currentSetting.spaceMountainBanking,
        ModConfig.currentSetting.spaceMountainBankStrength,
        currentRoll,
        lastSample,
        SpaceMountainTrackData.count);
  }
}
