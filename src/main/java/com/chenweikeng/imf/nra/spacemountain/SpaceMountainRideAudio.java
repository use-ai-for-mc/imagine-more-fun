package com.chenweikeng.imf.nra.spacemountain;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.audio.OpenAudioMcService;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance.Attenuation;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

/**
 * Continuous wind + rail-friction loops driven by the rider's live velocity and yaw rate. Both
 * loops are persistent {@link AbstractTickableSoundInstance}s started when {@link
 * SpaceMountainOverride#isActive()} flips true and stopped when it flips false. The volumes are
 * recomputed every client tick from the vehicle's per-tick position/yaw deltas.
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
 *   <li><b>wind</b>: gain rises with speed, kicks in only above ~8 blocks/s
 *   <li><b>rail</b>: gain rises with sharpness AND is speed-gated (no squeal when stopped)
 *   <li><b>rail pitch</b>: rises gently with speed (15–25%) to break up the loop's perceived
 *       repetition without sounding artificial
 * </ul>
 *
 * <p>Both sounds are constructed as listener-relative with attenuation disabled — the rider is
 * always "inside" them; no 3D positional panning.
 *
 * <p>Asset levels: pre-recorded sources differ in loudness, so each loop has its own {@code MAX_*}
 * ceiling. The source files were peak-normalized to roughly -2 dB / 0 dB (wind / rail) during the
 * project's audio bake — the {@code MAX_*_GAIN} constants below are tuned against those peaks so
 * the per-instance volume stays under {@code SoundEngine}'s 1.0 clamp across the full OAM range,
 * while still reproducing the legacy "rideAudioVolume=100%" loudness at the OAM=25% calibration
 * point.
 *
 * <p><b>Master loudness tracks OpenAudioMC.</b> The output is scaled by {@link
 * OpenAudioMcService#getCurrentVolume()} so the wind and rail loops grow louder when the user
 * cranks the OAM music slider and quieter when they pull it down — both layers stay in balance
 * without a separate config knob. Calibration: OAM=25% reproduces the legacy "rideAudioVolume=100%"
 * loudness; OAM=100% is roughly 4× that. The scaling is linear in OAM up to where the per-instance
 * volume hits 1.0 (≈ OAM=100 for wind, ≈ OAM=87 for rail — rail's source asset was already nearly
 * peak-normalized so its headroom is limited).
 */
public final class SpaceMountainRideAudio {

  private static final Identifier WIND_ID =
      Identifier.fromNamespaceAndPath("imaginemorefun", "ride.wind");
  private static final Identifier RAIL_ID =
      Identifier.fromNamespaceAndPath("imaginemorefun", "ride.rail_friction");

  // Per-loop output ceilings. Tuned against the asset peaks (wind ≈ -2 dB, rail ≈ 0 dB) so the
  // legacy "rideAudioVolume=100%" loudness lands at OAM=25%, and the per-instance volume stays
  // under SoundEngine's 1.0 clamp across the OAM range. Rail's ceiling is lower than wind's
  // because the rail source (a real train recorded at speed) is already louder and would
  // dominate the mix at full output.
  private static final float MAX_WIND_GAIN = 0.131f;
  private static final float MAX_RAIL_GAIN = 0.141f;

  // Signal thresholds (blocks/s for speed; blocks/s² for sharpness).
  private static final double WIND_LO_SPEED = 8.0;
  private static final double WIND_HI_SPEED = 22.0;
  // Wind volume rises as smoothstep(speed)^WIND_CURVE_POWER — matches aeroacoustic scaling
  // (turbulent
  // boundary-layer noise grows ~v³ in power; pure free-stream rush ~v²). 2.0 is a good middle.
  // Bump toward 3.0 for an even more dramatic "kicks in only at speed" feel.
  private static final double WIND_CURVE_POWER = 2.0;
  private static final double RAIL_SHARP_LO = 2.0;
  private static final double RAIL_SHARP_HI = 60.0;
  private static final double RAIL_SPEED_LO = 2.0;
  private static final double RAIL_SPEED_HI = 6.0;

  // Rail pitch range (1.0 = neutral). Rises with speed.
  private static final float RAIL_PITCH_LO = 0.90f;
  private static final float RAIL_PITCH_HI = 1.20f;

  // EMA smoothing — higher α reacts faster, lower lags more.
  private static final double SMOOTH_ALPHA = 0.35;

  // Slew limit on the volume field per tick — prevents pops on start/stop.
  private static final float VOLUME_SLEW = 0.04f;

  private static WindLoop windLoop;
  private static RailLoop railLoop;

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
      if (active) startLoops(mc);
      else stopLoops(mc);
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
    // Master scale = OpenAudioMC volume (0..100) / 12.5. Calibration: OAM=25 → 2.0, which
    // reproduces the loudness the project had at the legacy "rideAudioVolume=100%" setting.
    // OAM=50 → 4.0, OAM=100 → 8.0. The MAX_*_GAIN ceilings are sized against the amplified asset
    // peaks so the per-instance volume stays under SoundEngine's 1.0 clamp across the OAM range —
    // wind is fully linear to OAM=100, rail flattens slightly above OAM≈87 because its asset has
    // less headroom. When OAM isn't reporting a volume yet (-1), fall back to the 25% baseline so
    // users who haven't connected an OAM session still hear the calibrated mix.
    int oamVol = OpenAudioMcService.getInstance().getCurrentVolume();
    double effectiveVol = oamVol >= 0 ? oamVol : 25.0;
    double volScale = effectiveVol / 12.5;

    if (windLoop != null) {
      double frac = smoothstep(WIND_LO_SPEED, WIND_HI_SPEED, smoothedSpeed);
      float t = MAX_WIND_GAIN * (float) Math.pow(frac, WIND_CURVE_POWER);
      windLoop.target = (float) (t * volScale);
    }
    if (railLoop != null) {
      float vT =
          (float)
              (MAX_RAIL_GAIN
                  * smoothstep(RAIL_SHARP_LO, RAIL_SHARP_HI, sharpness)
                  * smoothstep(RAIL_SPEED_LO, RAIL_SPEED_HI, smoothedSpeed));
      float pT =
          RAIL_PITCH_LO
              + (RAIL_PITCH_HI - RAIL_PITCH_LO)
                  * (float) smoothstep(RAIL_SPEED_LO, WIND_HI_SPEED, smoothedSpeed);
      railLoop.targetVol = (float) (vT * volScale);
      railLoop.targetPitch = pT;
    }
  }

  private static void startLoops(Minecraft mc) {
    if (windLoop == null) {
      windLoop = new WindLoop();
      mc.getSoundManager().play(windLoop);
    }
    if (railLoop == null) {
      railLoop = new RailLoop();
      mc.getSoundManager().play(railLoop);
    }
    NotRidingAlertClient.LOGGER.info("[SpaceMountainRideAudio] ride loops started");
  }

  private static void stopLoops(Minecraft mc) {
    if (windLoop != null) {
      mc.getSoundManager().stop(windLoop);
      windLoop = null;
    }
    if (railLoop != null) {
      mc.getSoundManager().stop(railLoop);
      railLoop = null;
    }
    NotRidingAlertClient.LOGGER.info("[SpaceMountainRideAudio] ride loops stopped");
  }

  private static double smoothstep(double edge0, double edge1, double x) {
    double t = Math.max(0.0, Math.min(1.0, (x - edge0) / (edge1 - edge0)));
    return t * t * (3.0 - 2.0 * t);
  }

  private static class WindLoop extends AbstractTickableSoundInstance {
    volatile float target = 0.0f;

    WindLoop() {
      super(
          SoundEvent.createVariableRangeEvent(WIND_ID), SoundSource.AMBIENT, RandomSource.create());
      this.looping = true;
      this.delay = 0;
      this.relative = true;
      this.attenuation = Attenuation.NONE;
      this.volume = 0.0f;
      this.pitch = 1.0f;
    }

    @Override
    public boolean canStartSilent() {
      return true;
    }

    @Override
    public void tick() {
      float diff = target - volume;
      if (Math.abs(diff) <= VOLUME_SLEW) volume = target;
      else volume += Math.signum(diff) * VOLUME_SLEW;
    }
  }

  private static class RailLoop extends AbstractTickableSoundInstance {
    volatile float targetVol = 0.0f;
    volatile float targetPitch = 1.0f;

    RailLoop() {
      super(
          SoundEvent.createVariableRangeEvent(RAIL_ID), SoundSource.AMBIENT, RandomSource.create());
      this.looping = true;
      this.delay = 0;
      this.relative = true;
      this.attenuation = Attenuation.NONE;
      this.volume = 0.0f;
      this.pitch = 1.0f;
    }

    @Override
    public boolean canStartSilent() {
      return true;
    }

    @Override
    public void tick() {
      float vDiff = targetVol - volume;
      if (Math.abs(vDiff) <= VOLUME_SLEW) volume = targetVol;
      else volume += Math.signum(vDiff) * VOLUME_SLEW;
      // Lighter slew on pitch; coupled to playback rate so abrupt changes are audible.
      pitch += (targetPitch - pitch) * 0.1f;
    }
  }
}
