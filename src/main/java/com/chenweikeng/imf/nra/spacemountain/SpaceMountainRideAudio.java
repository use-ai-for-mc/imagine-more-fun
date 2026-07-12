package com.chenweikeng.imf.nra.spacemountain;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.audio.OpenAudioMcService;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Continuous wind + rail-friction loops driven by the rider's live velocity and yaw rate. Both
 * loops are persistent independent Java Sound clips started when {@link
 * SpaceMountainOverride#isActive()} flips true and stopped when it flips false. The volumes are
 * recomputed every client tick from the vehicle's per-tick position/yaw deltas. They deliberately
 * bypass Minecraft's sound engine so Dynamic FPS cannot attenuate them when the window is unfocused
 * or cancel them when its background volume is zero.
 *
 * <p>Signals (smoothed via EMA so they don't jitter at the loop level):
 *
 * <ul>
 *   <li><b>speed</b> = ‖Δpos‖ / 0.05 s — blocks/s
 *   <li><b>yawRate</b> = |Δyaw| / 0.05 s — deg/s
 *   <li><b>sharpness</b> = speed · radians(yawRate) — lateral acceleration proxy (blocks/s²)
 * </ul>
 *
 * <p>Mappings (smoothstep curves, all constants at top of file for fast tuning):
 *
 * <ul>
 *   <li><b>wind</b>: starts above 6 blocks/s, then grows into the full speed-driven rush
 *   <li><b>rail</b>: starts above 5 blocks/s, with extra gain from sharp turns
 * </ul>
 *
 * <p>Both sounds are mono, non-positional loops — the rider is always "inside" them; no 3D
 * attenuation or panning is applied.
 *
 * <p>Asset levels: pre-recorded sources differ in loudness, so each loop has its own {@code MAX_*}
 * ceiling. The source files were peak-normalized to roughly -2 dB / 0 dB (wind / rail) during the
 * project's audio bake — the {@code MAX_*_GAIN} constants below are tuned against those peaks for a
 * direct linear mapping from the OpenAudioMC slider that does not clip at the high end.
 *
 * <p><b>Master loudness tracks OpenAudioMC.</b> The output is scaled by {@link
 * OpenAudioMcService#getCurrentVolume()} so the wind and rail loops grow louder when the user
 * cranks the OAM music slider and quieter when they pull it down — both layers stay in balance
 * without a separate config knob. OAM=0/25/50/100% maps to 0/25/50/100% maximum wind gain; rail is
 * kept slightly lower because its source asset is louder.
 */
public final class SpaceMountainRideAudio {

  private static final IndependentRideAudioLoop WIND_LOOP =
      new IndependentRideAudioLoop("/assets/imaginemorefun/sounds/ride/wind.wav", "wind");
  private static final IndependentRideAudioLoop RAIL_LOOP =
      new IndependentRideAudioLoop(
          "/assets/imaginemorefun/sounds/ride/rail_friction.wav", "rail-friction");

  // Per-loop output ceilings. Tuned against the asset peaks (wind ≈ -2 dB, rail ≈ 0 dB) so the
  // legacy "rideAudioVolume=100%" loudness lands at OAM=25%, and the independent clip's linear
  // volume stays at or below 1.0 across the OAM range. Rail's ceiling is lower than wind's because
  // the rail source (a real train recorded at speed) is already louder and would dominate the mix
  // at full output.
  private static final float MAX_WIND_GAIN = 0.250f;
  private static final float MAX_RAIL_GAIN = 0.220f;

  // Signal thresholds (blocks/s for speed; blocks/s² for sharpness).
  private static final double WIND_LO_SPEED = 6.0;
  private static final double WIND_HI_SPEED = 18.0;
  // Wind volume rises as smoothstep(speed)^WIND_CURVE_POWER — matches aeroacoustic scaling
  // (turbulent
  // boundary-layer noise grows ~v³ in power; pure free-stream rush ~v²). The slightly softer
  // curve keeps the loop present through the coaster's medium-speed sections.
  private static final double WIND_CURVE_POWER = 1.5;
  private static final double RAIL_SHARP_LO = 2.0;
  private static final double RAIL_SHARP_HI = 35.0;
  private static final double RAIL_SPEED_LO = 5.0;
  private static final double RAIL_SPEED_HI = 10.0;
  private static final double RAIL_STRAIGHT_BED = 0.25;

  // EMA smoothing — higher α reacts faster, lower lags more.
  private static final double SMOOTH_ALPHA = 0.35;

  private static boolean wasActive = false;
  private static double prevX, prevY, prevZ;
  private static float prevYaw;
  private static boolean havePrev = false;
  private static double smoothedSpeed = 0.0;
  private static double smoothedYawRate = 0.0;

  private SpaceMountainRideAudio() {}

  public static void register() {
    ClientTickEvents.END_CLIENT_TICK.register(SpaceMountainRideAudio::onClientTick);
  }

  private static void onClientTick(Minecraft mc) {
    boolean active = SpaceMountainOverride.isActive() && mc.player != null && mc.level != null;

    if (active != wasActive) {
      if (active) startLoops();
      else stopLoops();
      wasActive = active;
    }
    if (!active) {
      havePrev = false;
      smoothedSpeed = 0.0;
      smoothedYawRate = 0.0;
      return;
    }

    Entity ref = mc.player.getVehicle() != null ? mc.player.getVehicle() : mc.player;
    double x = ref.getX();
    double y = ref.getY();
    double z = ref.getZ();
    float yaw = ref.getYRot();

    if (havePrev) {
      double dx = x - prevX, dy = y - prevY, dz = z - prevZ;
      double instSpeed = Math.sqrt(dx * dx + dy * dy + dz * dz) / 0.05; // blocks/s @ 20 TPS
      // Unwrap yaw delta into (-180, 180] before taking |·|.
      double dyaw = ((yaw - prevYaw) % 360f + 540f) % 360f - 180f;
      double instYawRate = Math.abs(dyaw) / 0.05;
      smoothedSpeed = SMOOTH_ALPHA * instSpeed + (1 - SMOOTH_ALPHA) * smoothedSpeed;
      smoothedYawRate = SMOOTH_ALPHA * instYawRate + (1 - SMOOTH_ALPHA) * smoothedYawRate;
    }
    prevX = x;
    prevY = y;
    prevZ = z;
    prevYaw = yaw;
    havePrev = true;

    double sharpness = smoothedSpeed * Math.toRadians(smoothedYawRate);
    // Direct linear mapping: OAM=0/25/50/100 → scale=0/1/2/4. With MAX_WIND_GAIN=0.25 this maps
    // maximum wind amplitude to exactly 0/25/50/100%. When OAM isn't reporting a volume yet (-1),
    // use 25% as the neutral fallback.
    int oamVol = OpenAudioMcService.getInstance().getCurrentVolume();
    double effectiveVol = oamVol >= 0 ? oamVol : 25.0;
    double volScale = effectiveVol / 25.0;

    double windFrac =
        Math.pow(smoothstep(WIND_LO_SPEED, WIND_HI_SPEED, smoothedSpeed), WIND_CURVE_POWER);
    WIND_LOOP.tick((float) (MAX_WIND_GAIN * windFrac * volScale));

    double cornerGain =
        RAIL_STRAIGHT_BED
            + (1.0 - RAIL_STRAIGHT_BED) * smoothstep(RAIL_SHARP_LO, RAIL_SHARP_HI, sharpness);
    double railFrac = smoothstep(RAIL_SPEED_LO, RAIL_SPEED_HI, smoothedSpeed);
    RAIL_LOOP.tick((float) (MAX_RAIL_GAIN * cornerGain * railFrac * volScale));
  }

  private static void startLoops() {
    boolean windStarted = WIND_LOOP.start();
    boolean railStarted = RAIL_LOOP.start();
    NotRidingAlertClient.LOGGER.info(
        "[SpaceMountainRideAudio] independent ride loops started (wind={}, rail={})",
        windStarted,
        railStarted);
  }

  private static void stopLoops() {
    WIND_LOOP.stop();
    RAIL_LOOP.stop();
    NotRidingAlertClient.LOGGER.info("[SpaceMountainRideAudio] independent ride loops stopped");
  }

  private static double smoothstep(double edge0, double edge1, double x) {
    double t = Math.max(0.0, Math.min(1.0, (x - edge0) / (edge1 - edge0)));
    return t * t * (3.0 - 2.0 * t);
  }
}
