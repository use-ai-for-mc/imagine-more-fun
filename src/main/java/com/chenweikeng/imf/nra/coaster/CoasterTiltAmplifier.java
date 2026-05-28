package com.chenweikeng.imf.nra.coaster;

import com.chenweikeng.imf.nra.ServerState;
import com.chenweikeng.imf.nra.config.ModConfig;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/**
 * Global coaster-tilt multiplier — scales the camera lean that SmoothCoasters applies, rather than
 * computing our own from a baked track.
 *
 * <p>SmoothCoasters drives the coaster camera by funnelling every server rotation packet through
 * {@code me.m56738.smoothcoasters.SmoothCoasters#setRotation(Quaternionfc, int)} — a target seat
 * pose (world-space orientation) that it interpolates and writes onto the camera. {@code
 * NraSmoothCoastersRotationMixin} intercepts that target pose and hands it here; we re-scale the
 * <em>bank</em> (roll) by the {@code coasterTiltMultiplier} config slider and hand the adjusted
 * pose back, so SmoothCoasters' own interpolation and camera write carry the new lean. {@code 1.0}
 * is a no-op (stock tilt), {@code 2.0} doubles the lean, {@code 0.0} flattens it. This applies to
 * every coaster SmoothCoasters tilts, not just Space Mountain.
 *
 * <p><b>Roll only.</b> We scale the sideways lean and leave heading (yaw) and slope (pitch) exactly
 * as the server sent them — drops and climbs are untouched. The pose is world-absolute (the seat
 * faces any compass direction), so the bank can't be read as a twist about a fixed body axis
 * without a singularity at due-south headings. Instead it's measured geometrically: project
 * world-up perpendicular to the seat's forward axis to get the "no-bank" up, then measure how far
 * the seat's actual up has rolled about forward from it. The delta roll is applied as a rotation
 * about that same world-space forward axis, which leaves the forward direction (the aim) untouched.
 *
 * <p>Still gated to ImagineFun ({@link ServerState#isImagineFunServer()}), consistent with the rest
 * of the mod — coaster tilt is unscaled on other servers even if they run SmoothCoasters.
 */
public final class CoasterTiltAmplifier {
  // Below this the forward axis is within ~0.6° of vertical (looking straight up/down): there's no
  // horizon to bank against, so the geometric roll is undefined — leave the pose alone.
  private static final float MIN_REF_UP_SQ = 1e-4f;

  // Multipliers this close to 1.0 are a no-op: skip the geometry and pass the pose straight
  // through.
  private static final double NEUTRAL_EPSILON = 1e-3;

  private CoasterTiltAmplifier() {}

  /**
   * Returns {@code pose} with its bank scaled by the coaster-tilt multiplier, or {@code pose}
   * unchanged when scaling is gated off (wrong server, multiplier ≈ 1.0, or a pose with no
   * measurable bank). Never mutates the input — a re-scaled pose is a fresh quaternion.
   */
  public static Quaternionfc amplify(Quaternionfc pose) {
    if (pose == null || !ServerState.isImagineFunServer()) {
      return pose;
    }
    double multiplier = ModConfig.currentSetting.coasterTiltMultiplier;
    if (Math.abs(multiplier - 1.0) < NEUTRAL_EPSILON) {
      return pose;
    }

    // Seat axes in world space (camera convention: forward is -Z, up is +Y).
    Vector3f forward = pose.transform(new Vector3f(0f, 0f, -1f));
    if (forward.lengthSquared() < 1e-9f) {
      return pose;
    }
    forward.normalize();
    Vector3f up = pose.transform(new Vector3f(0f, 1f, 0f));

    // The "no-bank" up: world-up projected onto the plane perpendicular to forward.
    float fDotWorldUp = forward.y; // (0,1,0) · forward
    Vector3f refUp =
        new Vector3f(0f, 1f, 0f)
            .sub(forward.x * fDotWorldUp, forward.y * fDotWorldUp, forward.z * fDotWorldUp);
    if (refUp.lengthSquared() < MIN_REF_UP_SQ) {
      return pose;
    }
    refUp.normalize();

    // Signed bank angle: how far `up` has rolled about `forward` away from refUp. A rotation about
    // +forward by θ takes refUp → cos θ·refUp + sin θ·side, so this θ is exactly that rotation.
    Vector3f side = new Vector3f(forward).cross(refUp); // forward × refUp, unit
    float roll = (float) Math.atan2(up.dot(side), up.dot(refUp));

    // Scale the bank to roll × multiplier by adding (multiplier − 1) × roll about forward.
    float deltaRoll = (float) ((multiplier - 1.0) * roll);
    Quaternionf delta =
        new Quaternionf().fromAxisAngleRad(forward.x, forward.y, forward.z, deltaRoll);
    return delta.mul(pose, new Quaternionf()); // delta ∘ pose: bank about forward, aim preserved
  }
}
