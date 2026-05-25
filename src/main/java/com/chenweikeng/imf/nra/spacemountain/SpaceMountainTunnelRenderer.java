package com.chenweikeng.imf.nra.spacemountain;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Random;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Cylindrical "tunnel cover" rendered around the rider while they are inside the Space Mountain
 * launch-tunnel volume. The cylinder is world-anchored along a tilted axis that follows the rider's
 * actual path — the launch tunnel climbs from Y≈69 at Z=150 up to Y≈83 at Z=198, so a flat-Z
 * cylinder would only intersect the path at one Y. The mesh is the canvas for tunnel screen
 * effects.
 *
 * <h2>Geometry</h2>
 *
 * <ul>
 *   <li>Axis: line from {@link #START} to {@link #END} (world-space). Centerline approximated from
 *       the centroid of cells changed during the 40-55 s warp window (the warp paints cells in a
 *       tube around the rider, so their per-slice centroid traces the track).
 *   <li>Radius {@link #RADIUS} blocks. Snug — the launch-tunnel mouth opening is only 8×6.
 *   <li>{@link #AZIMUTH_SEGMENTS} segments around the circumference × {@link #AXIAL_RINGS} along
 *       the axis. Inside-facing winding so back-face culling hides the outside.
 * </ul>
 */
public final class SpaceMountainTunnelRenderer {
  // Pure-white texture — every sampled texel is (1,1,1,1). With ImfRenderPipelines.opaqueScreen
  // (no blending, no alpha cutout, depth test on) the output is just vertex_color × lightmap.
  private static final Identifier SCREEN_TEXTURE =
      Identifier.fromNamespaceAndPath("imaginemorefun", "textures/particle/track.png");

  // Centerline endpoints. Derived from per-Z centroids of the captured warp animation:
  //   Z=150 → centroid (X=-257.5, Y=69)
  //   Z=198 → centroid (X=-257.5, Y=83)
  // X is constant at -257.5 over the bulk of the path. The pre-Z=150 entry has very few cells
  // (track is curving in from the launch room) — covering it would mis-align, so we start at
  // Z=150 and let the rider enter visually before the cover wraps them.
  private static final Vec3 START = new Vec3(-257.5, 68.4, 148.0);
  // END is cut short — pulled back down the same path axis to the Y≈79 point (Z≈185.8), well
  // before the cylinder's upper wall would clip the black dome blocks near the tunnel exit. The
  // axis keeps the path's height and tilt (not lowered), so the rider stays centered in the tube.
  private static final Vec3 END = new Vec3(-257.5, 79.0, 185.8);
  private static final double RADIUS = 4.0;

  // Mesh resolution. 24 × 16 = 384 quads (768 tris). Cheap.
  private static final int AZIMUTH_SEGMENTS = 24;
  private static final int AXIAL_RINGS = 16;

  // UV tile / scroll knobs for the placeholder "rings sliding forward" effect.
  private static final double UV_BLOCKS_PER_TILE = 4.0;
  private static final double UV_AZIMUTH_REPEAT = 1.0;
  private static final double UV_SCROLL_PER_SECOND = 0.8;

  // Base cylinder wall color (opaque). With the pure-white screen texture this is the literal
  // color on the wall: deep-space black. During the red phase the wall tints from this toward
  // RED_BG (see render()).
  private static final float COLOR_R = 0f;
  private static final float COLOR_G = 0f;
  private static final float COLOR_B = 0f;

  // Cylinder wall color during the warp — deep blue, matching the real ride's blue-projection
  // launch tunnel. Reached at enter=1 and held through the hyperspace phase.
  private static final float HYPERSPACE_COLOR_R = 0.10f;
  private static final float HYPERSPACE_COLOR_G = 0.25f;
  private static final float HYPERSPACE_COLOR_B = 0.70f;

  // Per-frame cylinder wall color — lerp(black, HYPERSPACE_COLOR, enter), set in render() and
  // read by addVertex. Single-threaded (render thread only), so a static scratch field is fine.
  private static float cylinderColorR = 0f;
  private static float cylinderColorG = 0f;
  private static float cylinderColorB = 0f;

  // Star field on the inner wall. Stars are tiny emissive quads affixed to the cylinder surface
  // (slightly inset toward the axis to avoid z-fighting with the black cylinder behind them).
  // Same gating as the cylinder — appears with the cover, disappears when the rider exits.
  private static final Identifier STAR_TEXTURE =
      Identifier.fromNamespaceAndPath("imaginemorefun", "textures/particle/star.png");
  // Stars are placed by jittered stratification: the cylinder surface is split into an
  // axial × azimuth grid and one star drops into each cell at a random spot inside it. Even
  // coverage with no clumping, but still natural (not a rigid lattice). Cell counts are picked
  // so the cells are roughly square given the cylinder's length-vs-circumference aspect ratio.
  private static final int STAR_AXIAL_CELLS = 56;
  private static final int STAR_AZIMUTH_CELLS = 25;
  private static final int STAR_COUNT = STAR_AXIAL_CELLS * STAR_AZIMUTH_CELLS; // 1400
  private static final float STAR_HALF_SIZE = 0.10f;
  // Generous inset so stars are clearly in front of the black wall — 0.05 was tight enough that
  // depth-buffer precision could land the star exactly on the wall and lose the depth test.
  private static final double STAR_INSET = 0.20;
  private static final long STAR_SEED = 0xCAFEBABEL;
  // Star field rotation around the cylinder axis — 60°/s (full revolution every 6 s), measured
  // from the ride video by the user. Positive = counterclockwise from the rider's forward-looking
  // view; negate to reverse.
  private static final double STAR_ROTATION_DEG_PER_SEC = 60.0;

  // Hyperspace mode: each star stretches into a glowing streak projected on the same point of
  // the cylinder wall, then fades back. STAR_COUNT positions are reused so the transition reads
  // as "the stars elongate into streaks." Active during the warp window with a soft fade in/out.
  private static final Identifier STREAK_TEXTURE =
      Identifier.fromNamespaceAndPath("imaginemorefun", "textures/particle/hyperspace_streak.png");
  // Master switch for the whole hyperspace transition (blue cylinder + streaks + exit dissolve).
  // Disabled while focusing on the Space Mountain purple phase — with this false, enter/exit
  // strengths stay 0, so the cylinder stays black and the rotating stars + purple rings persist
  // for the full window. This effect is Hyperspace-Mountain-specific and will likely be removed.
  private static final boolean HYPERSPACE_EFFECT_ENABLED = false;
  private static final int HYPERSPACE_START_SECONDS = 44;
  private static final int HYPERSPACE_END_SECONDS = 53;
  // Fade-in/out window in seconds — stars dim, streaks grow longer + brighter (and reverse).
  private static final double TRANSITION_SECONDS = 2.0;
  // Streak quad dimensions: axial half-length (long direction along cylinder axis) and tangential
  // half-width (short direction around circumference). The half-length scales with hyperspace
  // strength so streaks grow from a point into a line during the entry animation.
  private static final float STREAK_HALF_LENGTH_MAX = 3.0f;
  private static final float STREAK_HALF_WIDTH = 0.07f;
  // Streaks scroll along -axis (toward the tunnel entrance, which is also "downward" since the
  // tunnel climbs) for the warp-flow effect. Streaks fade in/out at the axial endpoints via
  // fadeAlpha so the position wrap is invisible.
  private static final double STREAK_SCROLL_BLOCKS_PER_SEC = 14.0;

  // Purple "double-circle" rings — the Space Mountain launch-tunnel effect. Each ring-pair is two
  // thin purple bands wrapping the full cylinder circumference; pairs are spaced RING_SPACING
  // apart along the axis and scroll toward the rider. Bands fade at the axial endpoints (via
  // fadeAlpha) so the scroll wrap is invisible.
  private static final float PURPLE_R = 0.55f;
  private static final float PURPLE_G = 0.15f;
  private static final float PURPLE_B = 1.0f;
  private static final double RING_INSET = 0.30; // ring radius offset inward from the wall
  private static final double RING_SPACING = 9.0; // axial gap between consecutive ring-pairs
  private static final double RING_PAIR_GAP = 1.3; // axial gap between the 2 bands of a pair
  private static final double RING_BAND_HALF_WIDTH = 0.20; // axial half-thickness of each band
  private static final double RING_SCROLL_BLOCKS_PER_SEC = 7.0;

  // --- Space Mountain tunnel phase timing -----------------------------------------------------
  // Ride-elapsed seconds. Relative durations measured from the ride video (5:32 tunnel entry →
  // 5:50 exit); anchored at TUNNEL_ENTER_SECONDS — shift that once the launch-tunnel entry
  // elapsed time is calibrated. Sequence: purple rings + stars → instant cut → full red (radial
  // streaks + striped wedge panels) → red fades out, tunnel ends.
  private static final double TUNNEL_ENTER_SECONDS = 41.0;
  // The black cylinder + rotating stars appear this many seconds before the purple rings begin,
  // so the rider sees an empty starfield tunnel briefly before the projection starts.
  private static final double STARS_ONLY_LEAD_SECONDS = 4.0;
  private static final double PURPLE_DURATION = 3.0; // purple rings + stars hold
  private static final double PURPLE_TO_RED_SECONDS = 0.0; // 0 = instant cut, no purple→red fade
  private static final double RED_DURATION = 10.0; // full red hold
  private static final double RED_FADE_SECONDS = 3.0; // red fades out, tunnel ends
  private static final double TUNNEL_END_SECONDS =
      TUNNEL_ENTER_SECONDS
          + PURPLE_DURATION
          + PURPLE_TO_RED_SECONDS
          + RED_DURATION
          + RED_FADE_SECONDS;

  // Red radial streaks (drawRedStreaks) — dormant, not currently drawn.
  private static final int RED_STREAK_COUNT = 36;
  private static final double RED_STREAK_INSET = 0.30; // radius offset inward from the wall
  private static final float RED_STREAK_HALF_WIDTH = 0.11f;
  private static final float RED_STREAK_R = 1.0f;
  private static final float RED_STREAK_G = 0.12f;
  private static final float RED_STREAK_B = 0.12f;
  // Red-phase background — during the red phase the cylinder wall tints from black toward this
  // dark red (by redA), so the bright stripes sit on a dark-red glow rather than pure black.
  private static final float RED_BG_R = 0.22f;
  private static final float RED_BG_G = 0.03f;
  private static final float RED_BG_B = 0.03f;

  // Red radial stripes — the dominant red-phase look. WEDGE_COUNT bright near-white-red bands
  // radiate from the tunnel centre (each spans the full length), evenly spaced, rotating CCW at
  // WEDGE_ROTATION_DEG_PER_SEC. The gaps between them show the dark-red background wall with the
  // star field through it; stars whose angle falls under a stripe are hidden.
  private static final int WEDGE_COUNT = 24;
  private static final int WEDGE_AZIMUTH_SUBDIV = 3; // quads across one band's angular span
  private static final int WEDGE_AXIAL_SEGS = 8; // axial subdivisions, for a smooth endpoint fade
  private static final double WEDGE_FILL_FRACTION = 0.4; // thin stripe; gaps show the background
  private static final double WEDGE_INSET = 0.10; // radius offset inward from the wall
  // Bright, near-white red — a hot glowing stripe against the dark-red background wall.
  private static final float WEDGE_RED_R = 1.0f, WEDGE_RED_G = 0.62f, WEDGE_RED_B = 0.58f;
  // The stripes rotate 1.5x the star-field rate, so they visibly sweep over the slower stars.
  private static final double WEDGE_ROTATION_DEG_PER_SEC = STAR_ROTATION_DEG_PER_SEC * 1.5;

  // Soft alpha fade at the axial endpoints so the cover doesn't pop on/off.
  private static final double FADE_AXIAL = 4.0;
  // Player must be near the axis (along its length) for the tunnel to render. The lateral check
  // is intentionally generous — the rider can lean / look around without losing the cover.
  private static final double LATERAL_TOLERANCE = 8.0;
  private static final double AXIAL_TOLERANCE = 8.0;

  // Precomputed circumference (cos, sin) per segment.
  private static final double[] COS = new double[AZIMUTH_SEGMENTS];
  private static final double[] SIN = new double[AZIMUTH_SEGMENTS];

  // Derived axis and cross-section basis.
  private static final Vec3 AXIS; // unit
  private static final double AXIS_LENGTH;
  private static final Vec3 U_VEC; // first perpendicular
  private static final Vec3 V_VEC; // second perpendicular = AXIS × U

  // Per-star fixed (axialDistance, angle) on the inner cylinder surface.
  private static final double[] STAR_S = new double[STAR_COUNT];
  private static final double[] STAR_THETA = new double[STAR_COUNT];

  static {
    for (int i = 0; i < AZIMUTH_SEGMENTS; i++) {
      double a = 2.0 * Math.PI * i / AZIMUTH_SEGMENTS;
      COS[i] = Math.cos(a);
      SIN[i] = Math.sin(a);
    }
    Vec3 d = END.subtract(START);
    AXIS_LENGTH = d.length();
    AXIS = d.scale(1.0 / AXIS_LENGTH);
    // Pick a reference vector not parallel to AXIS to seed U. Our axis is roughly +Z with a small
    // +Y component, so world-X is comfortably perpendicular.
    Vec3 ref = Math.abs(AXIS.x) < 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
    U_VEC = ref.subtract(AXIS.scale(ref.dot(AXIS))).normalize();
    V_VEC = AXIS.cross(U_VEC);
    Random rng = new Random(STAR_SEED);
    int idx = 0;
    for (int a = 0; a < STAR_AXIAL_CELLS; a++) {
      for (int z = 0; z < STAR_AZIMUTH_CELLS; z++) {
        // One star per (axial, azimuth) cell, jittered to a random spot within the cell.
        STAR_S[idx] = (a + rng.nextDouble()) / STAR_AXIAL_CELLS * AXIS_LENGTH;
        STAR_THETA[idx] = (z + rng.nextDouble()) / STAR_AZIMUTH_CELLS * (Math.PI * 2.0);
        idx++;
      }
    }
    NotRidingAlertClient.LOGGER.info(
        "[SpaceMountainTunnelRenderer] cylinder built: start={} end={} length={} r={} stars={}",
        START,
        END,
        AXIS_LENGTH,
        RADIUS,
        STAR_COUNT);
  }

  private SpaceMountainTunnelRenderer() {}

  public static void register() {
    WorldRenderEvents.AFTER_ENTITIES.register(SpaceMountainTunnelRenderer::render);
  }

  /**
   * True if a world point lies inside the launch-tunnel cylinder volume — within {@link #RADIUS} of
   * the axis and between {@link #START} and {@link #END}. {@link SpaceMountainTrackRenderer} uses
   * this to hide the in-tunnel track section so it doesn't show through the tunnel cover. A pure
   * function of the static geometry, safe to call at class-load time.
   */
  public static boolean isInsideCylinder(double wx, double wy, double wz) {
    double dx = wx - START.x, dy = wy - START.y, dz = wz - START.z;
    double s = dx * AXIS.x + dy * AXIS.y + dz * AXIS.z;
    if (s < 0.0 || s > AXIS_LENGTH) return false;
    double radialSq = dx * dx + dy * dy + dz * dz - s * s;
    return radialSq <= RADIUS * RADIUS;
  }

  /**
   * True once the red tunnel effect has begun — the instant purple-to-red cut at {@code
   * TUNNEL_ENTER_SECONDS + PURPLE_DURATION}. {@link SpaceMountainEntryTunnelSeal} latches its
   * entry-mouth seal on this, so the entrance is plugged exactly as the red phase starts.
   */
  public static boolean isRedPhaseStarted() {
    Integer elapsed = CurrentRideHolder.getElapsedSeconds();
    return elapsed != null && elapsed >= TUNNEL_ENTER_SECONDS + PURPLE_DURATION;
  }

  /**
   * Decompose a world-space offset from {@link #START} into (axialDistance, radialDistance). The
   * tunnel renders only while the player's axialDistance is inside [-AXIAL_TOLERANCE, length +
   * AXIAL_TOLERANCE] and radialDistance is within RADIUS + LATERAL_TOLERANCE.
   */
  // Set to true to bypass EVERY gate including the ride-active check — the cylinder renders in
  // any world (including SP) so you can fly to its world coords and inspect the geometry. Flip
  // back to false during normal play.
  private static final boolean DEBUG_ALWAYS_RENDER = false;

  // When debugging, also render both winding orders so culling can't hide the cylinder regardless
  // of which side of the wall the camera sits on.
  private static final boolean DEBUG_DOUBLE_SIDED = false;

  private static boolean shouldRender(Minecraft mc) {
    if (mc.player == null) return false;
    if (DEBUG_ALWAYS_RENDER) return true;
    if (!SpaceMountainOverride.isActive()) return false;
    Integer elapsed = CurrentRideHolder.getElapsedSeconds();
    if (elapsed == null
        || elapsed < TUNNEL_ENTER_SECONDS - STARS_ONLY_LEAD_SECONDS
        || elapsed >= TUNNEL_END_SECONDS) return false;
    // Position gates (axial / lateral) are intentionally left off — the cylinder axis hasn't
    // been re-calibrated against the actual rider path yet, so a lateral check would hide it
    // when it's misaligned. Add the checks back once START/END are correct.
    return true;
  }

  // Sub-second-resolution elapsed time. CurrentRideHolder.getElapsedSeconds() ticks at 1 Hz, but
  // we want smooth crossfades — anchor System.nanoTime() each time the integer second ticks and
  // interpolate between ticks. The anchor is reset whenever elapsed changes.
  private static int lastElapsedObserved = -1;
  private static long lastNanoAtObservation = 0L;

  private static double smoothElapsedSeconds() {
    Integer elapsed = CurrentRideHolder.getElapsedSeconds();
    if (elapsed == null) {
      lastElapsedObserved = -1;
      return -1.0;
    }
    long now = System.nanoTime();
    int e = elapsed.intValue();
    if (e != lastElapsedObserved) {
      lastElapsedObserved = e;
      lastNanoAtObservation = now;
    }
    double delta = (now - lastNanoAtObservation) / 1e9;
    if (delta < 0.0) delta = 0.0;
    else if (delta > 2.0) delta = 2.0; // clamp if observations stall
    return e + delta;
  }

  /**
   * Entry strength: 0 before the entering animation, ramps 0→1 over {@link #TRANSITION_SECONDS}
   * leading into {@link #HYPERSPACE_START_SECONDS}, then stays 1 for the rest of the ride. Drives
   * star fade-out + streak fade-in + streak length growth.
   */
  private static double enterStrength(double t) {
    if (!HYPERSPACE_EFFECT_ENABLED) return 0.0;
    if (t < 0) return 0.0;
    double startFade = HYPERSPACE_START_SECONDS - TRANSITION_SECONDS;
    if (t <= startFade) return 0.0;
    if (t >= HYPERSPACE_START_SECONDS) return 1.0;
    return (t - startFade) / TRANSITION_SECONDS;
  }

  /**
   * Exit strength: 0 during the warp, ramps 0→1 over {@link #TRANSITION_SECONDS} after {@link
   * #HYPERSPACE_END_SECONDS}, then stays 1. Multiplies into every layer's alpha (cylinder fill,
   * stars, streaks) so the exit beat dissolves the entire scene rather than restoring stars.
   */
  private static double exitStrength(double t) {
    if (!HYPERSPACE_EFFECT_ENABLED) return 0.0;
    if (t < 0) return 0.0;
    double endFade = HYPERSPACE_END_SECONDS + TRANSITION_SECONDS;
    if (t <= HYPERSPACE_END_SECONDS) return 0.0;
    if (t >= endFade) return 1.0;
    return (t - HYPERSPACE_END_SECONDS) / TRANSITION_SECONDS;
  }

  /** Purple-phase alpha: 1 during the purple hold, then an instant cut to 0 (no crossfade). */
  private static double purplePhaseAlpha(double t) {
    double rel = t - TUNNEL_ENTER_SECONDS;
    if (rel < 0.0) return 0.0;
    if (rel <= PURPLE_DURATION) return 1.0;
    double fadeEnd = PURPLE_DURATION + PURPLE_TO_RED_SECONDS;
    if (rel >= fadeEnd) return 0.0;
    return 1.0 - (rel - PURPLE_DURATION) / PURPLE_TO_RED_SECONDS;
  }

  /**
   * Red-phase alpha: 0 during purple, an instant cut to 1 at the purple→red switch, 1 through the
   * full red hold, ramps 1→0 over the final fade, 0 afterward.
   */
  private static double redPhaseAlpha(double t) {
    double rel = t - TUNNEL_ENTER_SECONDS;
    double redStart = PURPLE_DURATION;
    double redFull = PURPLE_DURATION + PURPLE_TO_RED_SECONDS;
    double redEnd = redFull + RED_DURATION;
    double fadeEnd = redEnd + RED_FADE_SECONDS;
    if (rel <= redStart || rel >= fadeEnd) return 0.0;
    if (rel < redFull) return (rel - redStart) / PURPLE_TO_RED_SECONDS;
    if (rel > redEnd) return 1.0 - (rel - redEnd) / RED_FADE_SECONDS;
    return 1.0;
  }

  /** Axial-distance fade — full alpha in the middle, 0 at the endpoints. */
  private static float fadeAlpha(double s) {
    double d = Math.min(s, AXIS_LENGTH - s);
    if (d >= FADE_AXIAL) return 1f;
    if (d <= 0) return 0f;
    return (float) (d / FADE_AXIAL);
  }

  private static void render(WorldRenderContext ctx) {
    Minecraft mc = Minecraft.getInstance();
    if (!shouldRender(mc)) return;

    Camera camera = mc.gameRenderer.getMainCamera();
    Vec3 cam = camera.position();

    PoseStack poseStack = ctx.matrices();
    PoseStack.Pose pose = poseStack.last();
    BufferSource bufferSource = mc.renderBuffers().bufferSource();
    RenderType renderType = ImfRenderPipelines.opaqueScreen(SCREEN_TEXTURE);
    VertexConsumer vc = bufferSource.getBuffer(renderType);

    int light = LightTexture.FULL_BRIGHT;
    int overlay = OverlayTexture.NO_OVERLAY;
    float camX = (float) cam.x;
    float camY = (float) cam.y;
    float camZ = (float) cam.z;

    // UV-scroll offset from real-time seconds — uses System.nanoTime so the effect keeps moving
    // even when the game logic ticks pause.
    double seconds = (System.nanoTime() % 1_000_000_000_000L) * 1e-9;
    float vScroll = (float) (-seconds * UV_SCROLL_PER_SECOND); // negative = rings move toward -Z

    // Phase clock — purple → instant cut → red → fade. purpleA gates the purple rings; redA gates
    // the red stripes and fades them out over the final RED_FADE_SECONDS.
    double t = smoothElapsedSeconds();
    float purpleA = (float) purplePhaseAlpha(t);
    float redA = (float) redPhaseAlpha(t);

    // Cylinder wall: black through the stars-only + purple phases, tinting to a dark red as the
    // red phase comes in (redA). redA does an instant 0->1 cut at the purple->red switch, so the
    // background snaps black->dark-red with the stripes, then fades back to black as red ends.
    cylinderColorR = COLOR_R + (RED_BG_R - COLOR_R) * redA;
    cylinderColorG = COLOR_G + (RED_BG_G - COLOR_G) * redA;
    cylinderColorB = COLOR_B + (RED_BG_B - COLOR_B) * redA;

    for (int ring = 0; ring < AXIAL_RINGS; ring++) {
      double s0 = AXIS_LENGTH * ring / AXIAL_RINGS;
      double s1 = AXIS_LENGTH * (ring + 1) / AXIAL_RINGS;
      Vec3 c0 = START.add(AXIS.scale(s0));
      Vec3 c1 = START.add(AXIS.scale(s1));
      float v0 = (float) (s0 / UV_BLOCKS_PER_TILE) + vScroll;
      float v1 = (float) (s1 / UV_BLOCKS_PER_TILE) + vScroll;
      float a0 = fadeAlpha(s0);
      float a1 = fadeAlpha(s1);
      if (a0 <= 0f && a1 <= 0f) continue;

      for (int seg = 0; seg < AZIMUTH_SEGMENTS; seg++) {
        int segNext = (seg + 1) % AZIMUTH_SEGMENTS;
        double oxA = RADIUS * (COS[seg] * U_VEC.x + SIN[seg] * V_VEC.x);
        double oyA = RADIUS * (COS[seg] * U_VEC.y + SIN[seg] * V_VEC.y);
        double ozA = RADIUS * (COS[seg] * U_VEC.z + SIN[seg] * V_VEC.z);
        double oxB = RADIUS * (COS[segNext] * U_VEC.x + SIN[segNext] * V_VEC.x);
        double oyB = RADIUS * (COS[segNext] * U_VEC.y + SIN[segNext] * V_VEC.y);
        double ozB = RADIUS * (COS[segNext] * U_VEC.z + SIN[segNext] * V_VEC.z);

        float u0 = (float) (seg / (double) AZIMUTH_SEGMENTS * UV_AZIMUTH_REPEAT);
        float u1 = (float) ((seg + 1) / (double) AZIMUTH_SEGMENTS * UV_AZIMUTH_REPEAT);

        float xa0 = (float) (c0.x + oxA - camX);
        float ya0 = (float) (c0.y + oyA - camY);
        float za0 = (float) (c0.z + ozA - camZ);
        float xb0 = (float) (c0.x + oxB - camX);
        float yb0 = (float) (c0.y + oyB - camY);
        float zb0 = (float) (c0.z + ozB - camZ);
        float xa1 = (float) (c1.x + oxA - camX);
        float ya1 = (float) (c1.y + oyA - camY);
        float za1 = (float) (c1.z + ozA - camZ);
        float xb1 = (float) (c1.x + oxB - camX);
        float yb1 = (float) (c1.y + oyB - camY);
        float zb1 = (float) (c1.z + ozB - camZ);

        // Inside-facing winding (normals point toward the axis).
        addVertex(vc, pose, xb0, yb0, zb0, u1, v0, a0, light, overlay);
        addVertex(vc, pose, xa0, ya0, za0, u0, v0, a0, light, overlay);
        addVertex(vc, pose, xa1, ya1, za1, u0, v1, a1, light, overlay);
        addVertex(vc, pose, xb1, yb1, zb1, u1, v1, a1, light, overlay);
        if (DEBUG_DOUBLE_SIDED) {
          // Outside-facing winding too — debug visibility from either side of the wall.
          addVertex(vc, pose, xa0, ya0, za0, u0, v0, a0, light, overlay);
          addVertex(vc, pose, xb0, yb0, zb0, u1, v0, a0, light, overlay);
          addVertex(vc, pose, xb1, yb1, zb1, u1, v1, a1, light, overlay);
          addVertex(vc, pose, xa1, ya1, za1, u0, v1, a1, light, overlay);
        }
      }
    }
    bufferSource.endBatch(renderType);

    // Purple rings — shown through the purple phase, gone at the instant purple→red cut.
    if (purpleA > 0f) {
      drawPurpleRings(pose, bufferSource, camX, camY, camZ, purpleA, seconds);
    }
    // Stars — persist through both phases, but stars whose angle falls under a red stripe fade
    // out as the red phase comes in (redA), so no stars show on the stripes.
    drawStars(pose, bufferSource, camX, camY, camZ, 1f, seconds, redA);
    // Red phase — thin solid-red stripes radiating around the wall, gaps left black.
    if (redA > 0f) {
      drawWedgePanels(pose, bufferSource, camX, camY, camZ, redA, seconds);
    }
  }

  /**
   * Bright pinpoint stars affixed to the inside wall. Emissive — bright against the dark wall.
   * {@code alphaMul} scales per-vertex alpha globally. {@code redA} is the red-phase strength:
   * stars whose displayed angle falls under a red stripe fade out by {@code (1 - redA)} so no star
   * shows on the stripes. The stripes rotate faster than the stars, so the in-stripe test accounts
   * for both rotations rather than cancelling them.
   */
  private static void drawStars(
      PoseStack.Pose pose,
      BufferSource bufferSource,
      float camX,
      float camY,
      float camZ,
      float alphaMul,
      double seconds,
      float redA) {
    if (alphaMul <= 0f) return;
    double starRadius = RADIUS - STAR_INSET;
    RenderType starType = RenderTypes.eyes(STAR_TEXTURE);
    VertexConsumer vc = bufferSource.getBuffer(starType);
    int light = LightTexture.FULL_BRIGHT;
    int overlay = OverlayTexture.NO_OVERLAY;
    float h = STAR_HALF_SIZE;
    double ax = AXIS.x, ay = AXIS.y, az = AXIS.z;
    // Whole field spins around the axis. Subtracting the offset = counterclockwise from the
    // rider's forward-looking view (the (U,V,AXIS) basis is right-handed, so increasing theta
    // reads clockwise looking down +axis).
    double rotation = Math.toRadians(STAR_ROTATION_DEG_PER_SEC) * seconds;
    // The stripes spin faster than the stars, so the in-stripe test below has to carry both
    // rotations rather than letting them cancel.
    double wedgeRotation = Math.toRadians(WEDGE_ROTATION_DEG_PER_SEC) * seconds;
    double wedgeSlot = (Math.PI * 2.0) / WEDGE_COUNT;
    double wedgeSpan = wedgeSlot * WEDGE_FILL_FRACTION;

    for (int i = 0; i < STAR_COUNT; i++) {
      double s = STAR_S[i];
      double theta = STAR_THETA[i] - rotation;
      // In-stripe stars fade out as the red phase rises. The star's displayed angle is
      // (THETA - rotation); it sits under a stripe when (THETA - rotation + wedgeRotation) mod
      // wedgeSlot lands in [0, wedgeSpan), so the faster stripes sweep stars in and out.
      float starAlpha = alphaMul;
      if (redA > 0f) {
        double phase = (STAR_THETA[i] - rotation + wedgeRotation) % wedgeSlot;
        if (phase < 0) phase += wedgeSlot;
        if (phase < wedgeSpan) starAlpha *= 1f - redA;
      }
      if (starAlpha <= 0f) continue;
      double cosTh = Math.cos(theta);
      double sinTh = Math.sin(theta);
      double rx = cosTh * U_VEC.x + sinTh * V_VEC.x;
      double ry = cosTh * U_VEC.y + sinTh * V_VEC.y;
      double rz = cosTh * U_VEC.z + sinTh * V_VEC.z;
      double tx = ay * rz - az * ry;
      double ty = az * rx - ax * rz;
      double tz = ax * ry - ay * rx;

      Vec3 c = START.add(AXIS.scale(s));
      double px = c.x + starRadius * rx - camX;
      double py = c.y + starRadius * ry - camY;
      double pz = c.z + starRadius * rz - camZ;

      float blX = (float) (px + h * (-tx - ax));
      float blY = (float) (py + h * (-ty - ay));
      float blZ = (float) (pz + h * (-tz - az));
      float brX = (float) (px + h * (tx - ax));
      float brY = (float) (py + h * (ty - ay));
      float brZ = (float) (pz + h * (tz - az));
      float trX = (float) (px + h * (tx + ax));
      float trY = (float) (py + h * (ty + ay));
      float trZ = (float) (pz + h * (tz + az));
      float tlX = (float) (px + h * (-tx + ax));
      float tlY = (float) (py + h * (-ty + ay));
      float tlZ = (float) (pz + h * (-tz + az));

      addQuadVertex(vc, pose, blX, blY, blZ, 0f, 0f, starAlpha, light, overlay);
      addQuadVertex(vc, pose, tlX, tlY, tlZ, 0f, 1f, starAlpha, light, overlay);
      addQuadVertex(vc, pose, trX, trY, trZ, 1f, 1f, starAlpha, light, overlay);
      addQuadVertex(vc, pose, brX, brY, brZ, 1f, 0f, starAlpha, light, overlay);
    }
    bufferSource.endBatch(starType);
  }

  /**
   * Long axial streaks projected onto the cylinder wall — the "entering hyperspace" effect. Reuses
   * each star's (s, theta) position so the transition reads as the stars elongating into streaks.
   * {@code alpha} controls visibility (fades in on entry, out on exit). {@code lengthFactor} scales
   * the axial half-length — during the entry animation streaks visibly grow from points to
   * full-length lines; during the exit beat they keep their full length but dim out instead of
   * retracting.
   */
  private static void drawStreaks(
      PoseStack.Pose pose,
      BufferSource bufferSource,
      float camX,
      float camY,
      float camZ,
      float alpha,
      float lengthFactor,
      double seconds) {
    if (alpha <= 0f || lengthFactor <= 0f) return;
    double streakRadius = RADIUS - STAR_INSET;
    RenderType streakType = RenderTypes.eyes(STREAK_TEXTURE);
    VertexConsumer vc = bufferSource.getBuffer(streakType);
    int light = LightTexture.FULL_BRIGHT;
    int overlay = OverlayTexture.NO_OVERLAY;
    float halfLen = STREAK_HALF_LENGTH_MAX * lengthFactor;
    float halfWidth = STREAK_HALF_WIDTH;
    double ax = AXIS.x, ay = AXIS.y, az = AXIS.z;
    // Axial scroll offset — subtracted from each streak's base position so streaks slide toward
    // -axis (the tunnel entrance / downward).
    double scroll = seconds * STREAK_SCROLL_BLOCKS_PER_SEC;

    for (int i = 0; i < STAR_COUNT; i++) {
      // Wrap the scrolled position into [0, AXIS_LENGTH). fadeAlpha tapers the streak to nothing
      // near both ends, so the wrap is never visible as a pop.
      double s = STAR_S[i] - scroll;
      s = ((s % AXIS_LENGTH) + AXIS_LENGTH) % AXIS_LENGTH;
      float wrapFade = fadeAlpha(s);
      if (wrapFade <= 0f) continue;
      float streakAlpha = alpha * wrapFade;
      double theta = STAR_THETA[i];
      double cosTh = Math.cos(theta);
      double sinTh = Math.sin(theta);
      double rx = cosTh * U_VEC.x + sinTh * V_VEC.x;
      double ry = cosTh * U_VEC.y + sinTh * V_VEC.y;
      double rz = cosTh * U_VEC.z + sinTh * V_VEC.z;
      double tx = ay * rz - az * ry;
      double ty = az * rx - ax * rz;
      double tz = ax * ry - ay * rx;

      Vec3 c = START.add(AXIS.scale(s));
      double px = c.x + streakRadius * rx - camX;
      double py = c.y + streakRadius * ry - camY;
      double pz = c.z + streakRadius * rz - camZ;

      // Rectangle: tangential (±halfWidth) × axial (±halfLen). Same inside-facing winding as
      // the stars (BL → TL → TR → BR) so back-face culling hides the outside view.
      float blX = (float) (px + halfWidth * -tx + halfLen * -ax);
      float blY = (float) (py + halfWidth * -ty + halfLen * -ay);
      float blZ = (float) (pz + halfWidth * -tz + halfLen * -az);
      float brX = (float) (px + halfWidth * tx + halfLen * -ax);
      float brY = (float) (py + halfWidth * ty + halfLen * -ay);
      float brZ = (float) (pz + halfWidth * tz + halfLen * -az);
      float trX = (float) (px + halfWidth * tx + halfLen * ax);
      float trY = (float) (py + halfWidth * ty + halfLen * ay);
      float trZ = (float) (pz + halfWidth * tz + halfLen * az);
      float tlX = (float) (px + halfWidth * -tx + halfLen * ax);
      float tlY = (float) (py + halfWidth * -ty + halfLen * ay);
      float tlZ = (float) (pz + halfWidth * -tz + halfLen * az);

      addQuadVertex(vc, pose, blX, blY, blZ, 0f, 0f, streakAlpha, light, overlay);
      addQuadVertex(vc, pose, tlX, tlY, tlZ, 0f, 1f, streakAlpha, light, overlay);
      addQuadVertex(vc, pose, trX, trY, trZ, 1f, 1f, streakAlpha, light, overlay);
      addQuadVertex(vc, pose, brX, brY, brZ, 1f, 0f, streakAlpha, light, overlay);
    }
    bufferSource.endBatch(streakType);
  }

  /**
   * Purple "double-circle" rings sliding down the tunnel — the Space Mountain launch-tunnel effect.
   * Each pair is two thin purple bands wrapping the full circumference; pairs are spaced {@link
   * #RING_SPACING} apart and scroll toward the rider (−axis, "downward"). Bands fade at the axial
   * endpoints so the scroll wrap is never visible as a pop.
   */
  private static void drawPurpleRings(
      PoseStack.Pose pose,
      BufferSource bufferSource,
      float camX,
      float camY,
      float camZ,
      float alpha,
      double seconds) {
    if (alpha <= 0f) return;
    double ringRadius = RADIUS - RING_INSET;
    RenderType type = RenderTypes.eyes(SCREEN_TEXTURE);
    VertexConsumer vc = bufferSource.getBuffer(type);
    int light = LightTexture.FULL_BRIGHT;
    int overlay = OverlayTexture.NO_OVERLAY;

    // scroll wraps within one RING_SPACING; numRings covers the tunnel plus a margin so a ring is
    // always entering at the far end as another leaves the near end.
    double scroll = (seconds * RING_SCROLL_BLOCKS_PER_SEC) % RING_SPACING;
    int numRings = (int) (AXIS_LENGTH / RING_SPACING) + 2;
    for (int k = 0; k < numRings; k++) {
      double sBase = k * RING_SPACING - scroll;
      for (int circle = 0; circle < 2; circle++) {
        double sCenter = sBase + circle * RING_PAIR_GAP;
        if (sCenter < 0 || sCenter > AXIS_LENGTH) continue;
        float bandAlpha = alpha * fadeAlpha(sCenter);
        if (bandAlpha <= 0f) continue;
        drawRingBand(vc, pose, sCenter, ringRadius, bandAlpha, camX, camY, camZ, light, overlay);
      }
    }
    bufferSource.endBatch(type);
  }

  /** One thin purple band wrapping the circumference at axial position {@code sCenter}. */
  private static void drawRingBand(
      VertexConsumer vc,
      PoseStack.Pose pose,
      double sCenter,
      double ringRadius,
      float bandAlpha,
      float camX,
      float camY,
      float camZ,
      int light,
      int overlay) {
    Vec3 c0 = START.add(AXIS.scale(sCenter - RING_BAND_HALF_WIDTH));
    Vec3 c1 = START.add(AXIS.scale(sCenter + RING_BAND_HALF_WIDTH));
    for (int seg = 0; seg < AZIMUTH_SEGMENTS; seg++) {
      int segNext = (seg + 1) % AZIMUTH_SEGMENTS;
      double oxA = ringRadius * (COS[seg] * U_VEC.x + SIN[seg] * V_VEC.x);
      double oyA = ringRadius * (COS[seg] * U_VEC.y + SIN[seg] * V_VEC.y);
      double ozA = ringRadius * (COS[seg] * U_VEC.z + SIN[seg] * V_VEC.z);
      double oxB = ringRadius * (COS[segNext] * U_VEC.x + SIN[segNext] * V_VEC.x);
      double oyB = ringRadius * (COS[segNext] * U_VEC.y + SIN[segNext] * V_VEC.y);
      double ozB = ringRadius * (COS[segNext] * U_VEC.z + SIN[segNext] * V_VEC.z);

      float xa0 = (float) (c0.x + oxA - camX);
      float ya0 = (float) (c0.y + oyA - camY);
      float za0 = (float) (c0.z + ozA - camZ);
      float xb0 = (float) (c0.x + oxB - camX);
      float yb0 = (float) (c0.y + oyB - camY);
      float zb0 = (float) (c0.z + ozB - camZ);
      float xa1 = (float) (c1.x + oxA - camX);
      float ya1 = (float) (c1.y + oyA - camY);
      float za1 = (float) (c1.z + ozA - camZ);
      float xb1 = (float) (c1.x + oxB - camX);
      float yb1 = (float) (c1.y + oyB - camY);
      float zb1 = (float) (c1.z + ozB - camZ);

      // Inside-facing winding, same order as the cylinder fill: b0 → a0 → a1 → b1.
      addPurpleVertex(vc, pose, xb0, yb0, zb0, bandAlpha, light, overlay);
      addPurpleVertex(vc, pose, xa0, ya0, za0, bandAlpha, light, overlay);
      addPurpleVertex(vc, pose, xa1, ya1, za1, bandAlpha, light, overlay);
      addPurpleVertex(vc, pose, xb1, yb1, zb1, bandAlpha, light, overlay);
    }
  }

  private static void addPurpleVertex(
      VertexConsumer vc,
      PoseStack.Pose pose,
      float x,
      float y,
      float z,
      float alpha,
      int light,
      int overlay) {
    vc.addVertex(pose, x, y, z)
        .setColor(PURPLE_R, PURPLE_G, PURPLE_B, alpha)
        .setUv(0.5f, 0.5f)
        .setOverlay(overlay)
        .setLight(light)
        .setNormal(pose, 0f, 1f, 0f);
  }

  private static void addQuadVertex(
      VertexConsumer vc,
      PoseStack.Pose pose,
      float x,
      float y,
      float z,
      float u,
      float v,
      float alpha,
      int light,
      int overlay) {
    vc.addVertex(pose, x, y, z)
        .setColor(1f, 1f, 1f, alpha)
        .setUv(u, v)
        .setOverlay(overlay)
        .setLight(light)
        .setNormal(pose, 0f, 1f, 0f);
  }

  private static void addVertex(
      VertexConsumer vc,
      PoseStack.Pose pose,
      float x,
      float y,
      float z,
      float u,
      float v,
      float alpha,
      int light,
      int overlay) {
    vc.addVertex(pose, x, y, z)
        .setColor(cylinderColorR, cylinderColorG, cylinderColorB, alpha)
        .setUv(u, v)
        .setOverlay(overlay)
        .setLight(light)
        .setNormal(pose, 0f, 1f, 0f);
  }

  /**
   * Red radial streaks — {@link #RED_STREAK_COUNT} streaks evenly spaced around the circumference,
   * each spanning the full tunnel length. From the rider's forward view they radiate from the
   * centre vanishing point. They rotate CCW with the star field (no axial scroll); the streak
   * texture's own end-fade tapers each at the tunnel mouths.
   */
  private static void drawRedStreaks(
      PoseStack.Pose pose,
      BufferSource bufferSource,
      float camX,
      float camY,
      float camZ,
      float alpha,
      double seconds) {
    if (alpha <= 0f) return;
    double r = RADIUS - RED_STREAK_INSET;
    RenderType type = RenderTypes.eyes(STREAK_TEXTURE);
    VertexConsumer vc = bufferSource.getBuffer(type);
    int light = LightTexture.FULL_BRIGHT;
    int overlay = OverlayTexture.NO_OVERLAY;
    double ax = AXIS.x, ay = AXIS.y, az = AXIS.z;
    double rotation = Math.toRadians(STAR_ROTATION_DEG_PER_SEC) * seconds;
    float hw = RED_STREAK_HALF_WIDTH;

    for (int i = 0; i < RED_STREAK_COUNT; i++) {
      double theta = i / (double) RED_STREAK_COUNT * (Math.PI * 2.0) - rotation;
      double cosTh = Math.cos(theta);
      double sinTh = Math.sin(theta);
      double rx = cosTh * U_VEC.x + sinTh * V_VEC.x;
      double ry = cosTh * U_VEC.y + sinTh * V_VEC.y;
      double rz = cosTh * U_VEC.z + sinTh * V_VEC.z;
      double tx = ay * rz - az * ry;
      double ty = az * rx - ax * rz;
      double tz = ax * ry - ay * rx;

      double p0x = START.x + r * rx - camX;
      double p0y = START.y + r * ry - camY;
      double p0z = START.z + r * rz - camZ;
      double p1x = END.x + r * rx - camX;
      double p1y = END.y + r * ry - camY;
      double p1z = END.z + r * rz - camZ;

      float blX = (float) (p0x - hw * tx);
      float blY = (float) (p0y - hw * ty);
      float blZ = (float) (p0z - hw * tz);
      float brX = (float) (p0x + hw * tx);
      float brY = (float) (p0y + hw * ty);
      float brZ = (float) (p0z + hw * tz);
      float trX = (float) (p1x + hw * tx);
      float trY = (float) (p1y + hw * ty);
      float trZ = (float) (p1z + hw * tz);
      float tlX = (float) (p1x - hw * tx);
      float tlY = (float) (p1y - hw * ty);
      float tlZ = (float) (p1z - hw * tz);

      // Inside-facing winding BL → TL → TR → BR. V runs 0→1 along the axis so the streak
      // texture's end-fade tapers the streak at both tunnel mouths.
      addColorVertex(
          vc,
          pose,
          blX,
          blY,
          blZ,
          0f,
          0f,
          RED_STREAK_R,
          RED_STREAK_G,
          RED_STREAK_B,
          alpha,
          light,
          overlay);
      addColorVertex(
          vc,
          pose,
          tlX,
          tlY,
          tlZ,
          0f,
          1f,
          RED_STREAK_R,
          RED_STREAK_G,
          RED_STREAK_B,
          alpha,
          light,
          overlay);
      addColorVertex(
          vc,
          pose,
          trX,
          trY,
          trZ,
          1f,
          1f,
          RED_STREAK_R,
          RED_STREAK_G,
          RED_STREAK_B,
          alpha,
          light,
          overlay);
      addColorVertex(
          vc,
          pose,
          brX,
          brY,
          brZ,
          1f,
          0f,
          RED_STREAK_R,
          RED_STREAK_G,
          RED_STREAK_B,
          alpha,
          light,
          overlay);
    }
    bufferSource.endBatch(type);
  }

  /**
   * Radial striped bands — {@link #WEDGE_COUNT} solid bright-red bands radiating from the tunnel
   * centre, each spanning the full tunnel length, evenly spaced around the circumference like a
   * sunburst. The whole set rotates CCW at {@link #WEDGE_ROTATION_DEG_PER_SEC} — faster than the
   * star field. Each band is subdivided axially only so the per-vertex {@link #fadeAlpha} tapers it
   * smoothly at the tunnel mouths — there are no circumferential rungs (those would line up across
   * bands into a concentric-ring bullseye).
   */
  private static void drawWedgePanels(
      PoseStack.Pose pose,
      BufferSource bufferSource,
      float camX,
      float camY,
      float camZ,
      float alpha,
      double seconds) {
    if (alpha <= 0f) return;
    double r = RADIUS - WEDGE_INSET;
    RenderType type = RenderTypes.eyes(SCREEN_TEXTURE);
    VertexConsumer vc = bufferSource.getBuffer(type);
    int light = LightTexture.FULL_BRIGHT;
    int overlay = OverlayTexture.NO_OVERLAY;
    double rotation = Math.toRadians(WEDGE_ROTATION_DEG_PER_SEC) * seconds;
    double slot = (Math.PI * 2.0) / WEDGE_COUNT;
    double wedgeSpan = slot * WEDGE_FILL_FRACTION;

    for (int k = 0; k < WEDGE_COUNT; k++) {
      double wedgeStart = k * slot - rotation; // CCW, same direction as the stars
      float cr = WEDGE_RED_R, cg = WEDGE_RED_G, cb = WEDGE_RED_B; // every stripe is red
      for (int axSeg = 0; axSeg < WEDGE_AXIAL_SEGS; axSeg++) {
        double s0 = (double) axSeg / WEDGE_AXIAL_SEGS * AXIS_LENGTH;
        double s1 = (double) (axSeg + 1) / WEDGE_AXIAL_SEGS * AXIS_LENGTH;
        float a0 = alpha * fadeAlpha(s0);
        float a1 = alpha * fadeAlpha(s1);
        if (a0 <= 0f && a1 <= 0f) continue;
        Vec3 cA = START.add(AXIS.scale(s0));
        Vec3 cB = START.add(AXIS.scale(s1));
        for (int sub = 0; sub < WEDGE_AZIMUTH_SUBDIV; sub++) {
          double tLo = wedgeStart + wedgeSpan * sub / WEDGE_AZIMUTH_SUBDIV;
          double tHi = wedgeStart + wedgeSpan * (sub + 1) / WEDGE_AZIMUTH_SUBDIV;
          double cLo = Math.cos(tLo), sLo = Math.sin(tLo);
          double cHi = Math.cos(tHi), sHi = Math.sin(tHi);
          double oLoX = r * (cLo * U_VEC.x + sLo * V_VEC.x);
          double oLoY = r * (cLo * U_VEC.y + sLo * V_VEC.y);
          double oLoZ = r * (cLo * U_VEC.z + sLo * V_VEC.z);
          double oHiX = r * (cHi * U_VEC.x + sHi * V_VEC.x);
          double oHiY = r * (cHi * U_VEC.y + sHi * V_VEC.y);
          double oHiZ = r * (cHi * U_VEC.z + sHi * V_VEC.z);
          float xLo0 = (float) (cA.x + oLoX - camX);
          float yLo0 = (float) (cA.y + oLoY - camY);
          float zLo0 = (float) (cA.z + oLoZ - camZ);
          float xHi0 = (float) (cA.x + oHiX - camX);
          float yHi0 = (float) (cA.y + oHiY - camY);
          float zHi0 = (float) (cA.z + oHiZ - camZ);
          float xLo1 = (float) (cB.x + oLoX - camX);
          float yLo1 = (float) (cB.y + oLoY - camY);
          float zLo1 = (float) (cB.z + oLoZ - camZ);
          float xHi1 = (float) (cB.x + oHiX - camX);
          float yHi1 = (float) (cB.y + oHiY - camY);
          float zHi1 = (float) (cB.z + oHiZ - camZ);
          // Inside-facing winding: (hi,s0) → (lo,s0) → (lo,s1) → (hi,s1).
          addColorVertex(vc, pose, xHi0, yHi0, zHi0, 0f, 0f, cr, cg, cb, a0, light, overlay);
          addColorVertex(vc, pose, xLo0, yLo0, zLo0, 1f, 0f, cr, cg, cb, a0, light, overlay);
          addColorVertex(vc, pose, xLo1, yLo1, zLo1, 1f, 1f, cr, cg, cb, a1, light, overlay);
          addColorVertex(vc, pose, xHi1, yHi1, zHi1, 0f, 1f, cr, cg, cb, a1, light, overlay);
        }
      }
    }
    bufferSource.endBatch(type);
  }

  private static void addColorVertex(
      VertexConsumer vc,
      PoseStack.Pose pose,
      float x,
      float y,
      float z,
      float u,
      float v,
      float r,
      float g,
      float b,
      float alpha,
      int light,
      int overlay) {
    vc.addVertex(pose, x, y, z)
        .setColor(r, g, b, alpha)
        .setUv(u, v)
        .setOverlay(overlay)
        .setLight(light)
        .setNormal(pose, 0f, 1f, 0f);
  }
}
