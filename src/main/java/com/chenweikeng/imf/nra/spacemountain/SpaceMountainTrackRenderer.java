package com.chenweikeng.imf.nra.spacemountain;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
 * Renders the Space/Hyperspace Mountain coaster track as rail-and-spine tubes that hover just below
 * the vehicle path. Geometry is built at startup from the SM entry in {@link
 * com.chenweikeng.imf.nra.coaster.CoasterTrackData} (the baked recording {@code dome_track.bin}) —
 * each sample's (yaw, pitch) becomes an orthonormal frame so the rails curve and dive, and the
 * per-sample {@code roll} (bank) angle rotates that frame around its forward axis so the rails tilt
 * into turns instead of lying flat.
 *
 * <p>Render type is {@link RenderTypes#entityTranslucent} — lightmap-respecting and depth-tested,
 * drawn at a low fixed lightmap coord with vertex colour modulated to a dim dark-grey metal ({@code
 * COLOR_R/G/B = 0.08/0.08/0.09}). The track reads as moody structure rather than a glowing line;
 * its brightness is governed by the {@code TRACK_BLOCK_LIGHT}/{@code TRACK_SKY_LIGHT} constants,
 * not the dome's own gloom. Quads are emitted with {@link BufferSource}; for ~1200 samples × 2
 * rails × 6 cross-section vertices we get ~14k quads per frame, well within the per-frame budget
 * for modern GPUs.
 *
 * <p>Adjustable knobs at the top: tube radius, rail separation, vertical offset under the vehicle,
 * cross-section subdivision count.
 */
public final class SpaceMountainTrackRenderer {
  private static final Identifier TRACK_TEXTURE =
      Identifier.fromNamespaceAndPath("imaginemorefun", "textures/particle/track.png");

  private static final double VEHICLE_Y_OFFSET =
      0.1; // +0.1 = track sits slightly above recorded vehicle pos (negative = below)
  private static final double RAIL_HALF_SEPARATION = 0.7; // rails span ±0.7 around centerline
  private static final double SPINE_DROP = 0.6; // center spine sits this far below the rails
  private static final float TUBE_RADIUS = 0.11f;
  private static final float SPINE_RADIUS =
      0.15f; // spine is the structural backbone — slightly thicker
  private static final int CROSS_SECTION_VERTS = 6; // hex cross-section
  // Triangular bracing: each crosstie position has two diagonal struts (left rail → spine, right
  // rail → spine), forming the V/triangle shape seen on real coaster track.
  private static final int CROSSTIE_STRIDE = 6;
  private static final float STRUT_RADIUS = 0.06f;
  // Vertex-color modulation of the (white) track.png texture — effectively the colour you see. A
  // dim, slightly-cool dark metal, kept low so the rails read as moody structure rather than a
  // glowing neon line. Bump toward 1.0 to brighten, drop toward 0 to fade back out.
  private static final float COLOR_R = 0.08f;
  private static final float COLOR_G = 0.08f;
  private static final float COLOR_B = 0.09f;
  private static final float COLOR_A = 0.45f;
  // Fixed lightmap coords (block-light × sky-light, 0..15) the track is drawn at — independent of
  // the dome's own gloom. Kept modest so the track stays subdued; raise toward 15/15 to brighten.
  private static final int TRACK_BLOCK_LIGHT = 4;
  private static final int TRACK_SKY_LIGHT = 0;
  // The rider doesn't see the track until they've cleared the launch tunnel. Skip rendering for
  // the first ~40 s of the ride. The bake subsamples 20 Hz → 6.67 Hz (every 3rd), so 40 s ≈
  // sample 267. Tweak if the recording or subsampling stride changes.
  private static final int RENDER_START_SAMPLE = 267;
  // Number of running surfaces along the track: 0=left rail, 1=right rail, 2=spine.
  private static final int RAIL_COUNT = 3;
  private static final double[] RAIL_LATERAL = {-RAIL_HALF_SEPARATION, RAIL_HALF_SEPARATION, 0};
  private static final double[] RAIL_VERTICAL = {0, 0, -SPINE_DROP};
  private static final float[] RAIL_RADIUS = {TUBE_RADIUS, TUBE_RADIUS, SPINE_RADIUS};

  // Pre-computed geometry. Layout: [sample][RAIL_COUNT * N cross-section verts]. Stored as doubles
  // because positions are world-space and far from origin; conversion to camera-space float
  // happens per-frame at draw time.
  private static int sampleCount = 0;
  private static double[] vertX = new double[0];
  private static double[] vertY = new double[0];
  private static double[] vertZ = new double[0];

  // Per-sample flag: true if the sample's centerline is inside the launch-tunnel cylinder volume
  // (SpaceMountainTunnelRenderer.isInsideCylinder). In-cylinder rail segments and struts are
  // skipped
  // so the track doesn't show through the tunnel cover. Precomputed once — the cylinder is static.
  private static boolean[] insideCyl = new boolean[0];

  // Triangular-bracing struts: each strut is a thin tube with 2 cross-section rings (start, end).
  // Two struts per crosstie position (left-rail → spine, right-rail → spine). Layout per strut:
  // 2 rings × N verts. Total verts = strutCount * 2 * N.
  private static int strutCount = 0;
  private static double[] strutVertX = new double[0];
  private static double[] strutVertY = new double[0];
  private static double[] strutVertZ = new double[0];

  // Track rendering enabled by default. Toggle off via the debug bridge:
  // java.import("...SpaceMountainTrackRenderer"):setEnabled(false)
  private static volatile boolean ENABLED = true;

  static {
    loadAndBuild();
  }

  private SpaceMountainTrackRenderer() {}

  public static void register() {
    WorldRenderEvents.AFTER_ENTITIES.register(SpaceMountainTrackRenderer::render);
  }

  public static void setEnabled(boolean enabled) {
    ENABLED = enabled;
    NotRidingAlertClient.LOGGER.info("[SpaceMountainTrackRenderer] enabled={}", enabled);
  }

  public static boolean isEnabled() {
    return ENABLED;
  }

  private static void loadAndBuild() {
    try {
      com.chenweikeng.imf.nra.coaster.CoasterTrackData.TrackSamples samples =
          com.chenweikeng.imf.nra.coaster.CoasterTrackData.forRide(
              com.chenweikeng.imf.nra.ride.RideName.SPACE_MOUNTAIN);
      int n = samples.count;
      if (n < 2) {
        NotRidingAlertClient.LOGGER.error(
            "[SpaceMountainTrackRenderer] no track data — track will not render");
        return;
      }

      double[] x = new double[n];
      double[] y = new double[n];
      double[] z = new double[n];
      float[] yaw = new float[n];
      float[] pitch = new float[n];
      float[] roll = new float[n];
      for (int i = 0; i < n; i++) {
        x[i] = samples.x[i];
        y[i] = samples.y[i] + VEHICLE_Y_OFFSET;
        z[i] = samples.z[i];
        yaw[i] = samples.yaw[i];
        pitch[i] = samples.pitch[i];
        roll[i] = samples.roll[i];
      }

      // Mask out the track section inside the launch-tunnel cylinder so it doesn't show through the
      // tunnel cover. Precomputed against the static cylinder volume.
      boolean[] inCyl = new boolean[n];
      for (int i = 0; i < n; i++) {
        inCyl[i] = SpaceMountainTunnelRenderer.isInsideCylinder(x[i], y[i], z[i]);
      }
      insideCyl = inCyl;

      // For each sample build an orthonormal basis (forward, right, up) from yaw/pitch, then
      // bank it: rotate (right, up) around forward by the baked roll angle so the rails tilt
      // into turns. forward = view direction in MC convention.
      double[] fx = new double[n], fy = new double[n], fz = new double[n];
      double[] rx = new double[n], ry = new double[n], rz = new double[n];
      double[] ux = new double[n], uy = new double[n], uz = new double[n];
      for (int i = 0; i < n; i++) {
        double yawRad = Math.toRadians(yaw[i]);
        double pitchRad = Math.toRadians(pitch[i]);
        double cosP = Math.cos(pitchRad);
        fx[i] = -Math.sin(yawRad) * cosP;
        fy[i] = -Math.sin(pitchRad);
        fz[i] = Math.cos(yawRad) * cosP;
        // Horizontal "right": cross(forward, world_up). Equivalent: yaw rotated 90°.
        rx[i] = -Math.cos(yawRad);
        ry[i] = 0;
        rz[i] = -Math.sin(yawRad);
        // up = right × forward (so it points "up" relative to track surface)
        ux[i] = ry[i] * fz[i] - rz[i] * fy[i];
        uy[i] = rz[i] * fx[i] - rx[i] * fz[i];
        uz[i] = rx[i] * fy[i] - ry[i] * fx[i];
        // Bank: rotate the (right, up) frame around forward by roll[i]. They are orthonormal,
        // so the rotation keeps them unit-length; both flow through to the cross-sections and
        // lateral rail offsets, so the whole track cross-section tilts into the turn.
        double rollRad = Math.toRadians(roll[i]);
        if (rollRad != 0.0) {
          double cr = Math.cos(rollRad), sr = Math.sin(rollRad);
          double nrx = cr * rx[i] + sr * ux[i];
          double nry = cr * ry[i] + sr * uy[i];
          double nrz = cr * rz[i] + sr * uz[i];
          double nux = -sr * rx[i] + cr * ux[i];
          double nuy = -sr * ry[i] + cr * uy[i];
          double nuz = -sr * rz[i] + cr * uz[i];
          rx[i] = nrx;
          ry[i] = nry;
          rz[i] = nrz;
          ux[i] = nux;
          uy[i] = nuy;
          uz[i] = nuz;
        }
        // up is unit-length by construction; safety re-normalize anyway.
        double ulen = Math.sqrt(ux[i] * ux[i] + uy[i] * uy[i] + uz[i] * uz[i]);
        if (ulen > 1e-9) {
          ux[i] /= ulen;
          uy[i] /= ulen;
          uz[i] /= ulen;
        }
      }

      // Pre-compute angle samples around cross-section.
      double[] cosA = new double[CROSS_SECTION_VERTS];
      double[] sinA = new double[CROSS_SECTION_VERTS];
      for (int k = 0; k < CROSS_SECTION_VERTS; k++) {
        double theta = (k * 2.0 * Math.PI) / CROSS_SECTION_VERTS;
        cosA[k] = Math.cos(theta);
        sinA[k] = Math.sin(theta);
      }

      // ---- Rails + spine: build vertex grid [sample][rail*N + k] -> world pos. ----
      int vertsPerSample = RAIL_COUNT * CROSS_SECTION_VERTS;
      int total = n * vertsPerSample;
      double[] vx = new double[total];
      double[] vy = new double[total];
      double[] vz = new double[total];
      for (int i = 0; i < n; i++) {
        for (int rail = 0; rail < RAIL_COUNT; rail++) {
          double anchorX = x[i] + RAIL_LATERAL[rail] * rx[i] + RAIL_VERTICAL[rail] * ux[i];
          double anchorY = y[i] + RAIL_LATERAL[rail] * ry[i] + RAIL_VERTICAL[rail] * uy[i];
          double anchorZ = z[i] + RAIL_LATERAL[rail] * rz[i] + RAIL_VERTICAL[rail] * uz[i];
          float radius = RAIL_RADIUS[rail];
          int base = i * vertsPerSample + rail * CROSS_SECTION_VERTS;
          for (int k = 0; k < CROSS_SECTION_VERTS; k++) {
            double offX = radius * (cosA[k] * rx[i] + sinA[k] * ux[i]);
            double offY = radius * (cosA[k] * ry[i] + sinA[k] * uy[i]);
            double offZ = radius * (cosA[k] * rz[i] + sinA[k] * uz[i]);
            vx[base + k] = anchorX + offX;
            vy[base + k] = anchorY + offY;
            vz[base + k] = anchorZ + offZ;
          }
        }
      }
      sampleCount = n;
      vertX = vx;
      vertY = vy;
      vertZ = vz;

      // ---- Bracing: V-struts only (left → spine, right → spine) per crosstie sample. X-bracing
      // between consecutive crossties was tried and dropped — the diagonals read as visual noise
      // when the rider passes them at speed. Only V-struts at >= RENDER_START_SAMPLE are emitted
      // to match the rail-render gate. ----
      int crosstieSamples = (n + CROSSTIE_STRIDE - 1) / CROSSTIE_STRIDE;
      int maxStruts = crosstieSamples * 2 + 2;
      double[] strX = new double[maxStruts * 2 * CROSS_SECTION_VERTS];
      double[] strY = new double[maxStruts * 2 * CROSS_SECTION_VERTS];
      double[] strZ = new double[maxStruts * 2 * CROSS_SECTION_VERTS];
      int strutIdx = 0;
      for (int i = 0; i < n; i += CROSSTIE_STRIDE) {
        if (i < RENDER_START_SAMPLE || inCyl[i]) continue; // skip launch-tunnel + in-cylinder
        double leftX = x[i] - RAIL_HALF_SEPARATION * rx[i];
        double leftY = y[i] - RAIL_HALF_SEPARATION * ry[i];
        double leftZ = z[i] - RAIL_HALF_SEPARATION * rz[i];
        double rightX = x[i] + RAIL_HALF_SEPARATION * rx[i];
        double rightY = y[i] + RAIL_HALF_SEPARATION * ry[i];
        double rightZ = z[i] + RAIL_HALF_SEPARATION * rz[i];
        double spineX = x[i] - SPINE_DROP * ux[i];
        double spineY = y[i] - SPINE_DROP * uy[i];
        double spineZ = z[i] - SPINE_DROP * uz[i];
        // V-strut 1: left rail → spine
        if (writeStrut(
            strX,
            strY,
            strZ,
            strutIdx,
            leftX,
            leftY,
            leftZ,
            spineX,
            spineY,
            spineZ,
            STRUT_RADIUS,
            cosA,
            sinA)) {
          strutIdx++;
        }
        // V-strut 2: right rail → spine
        if (writeStrut(
            strX,
            strY,
            strZ,
            strutIdx,
            rightX,
            rightY,
            rightZ,
            spineX,
            spineY,
            spineZ,
            STRUT_RADIUS,
            cosA,
            sinA)) {
          strutIdx++;
        }
      }
      strutCount = strutIdx;
      strutVertX = strX;
      strutVertY = strY;
      strutVertZ = strZ;

      NotRidingAlertClient.LOGGER.info(
          "[SpaceMountainTrackRenderer] loaded {} samples → {} rail verts, {} bracing struts",
          n,
          total,
          strutCount);
    } catch (Exception e) {
      NotRidingAlertClient.LOGGER.error("[SpaceMountainTrackRenderer] load failed", e);
    }
  }

  private static void render(WorldRenderContext ctx) {
    if (!ENABLED) return;
    if (sampleCount < 2) return;
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null) return;
    // Only render while the rider is actually on Space/Hyperspace Mountain.
    if (!SpaceMountainOverride.isActive()) return;
    drawTrack(ctx, mc);
  }

  private static void drawTrack(WorldRenderContext ctx, Minecraft mc) {
    Camera camera = mc.gameRenderer.getMainCamera();
    Vec3 cam = camera.position();

    PoseStack poseStack = ctx.matrices();
    PoseStack.Pose pose = poseStack.last();
    BufferSource bufferSource = mc.renderBuffers().bufferSource();
    // Non-emissive translucent: respects the lightmap (low fixed light coord renders the track as
    // dim metal) AND the depth buffer (track is occluded by blocks in front of it).
    RenderType renderType = RenderTypes.entityTranslucent(TRACK_TEXTURE);
    VertexConsumer vc = bufferSource.getBuffer(renderType);

    // Low fixed lightmap coord: block-light=8, sky-light=0 → track reads as dim metal in any
    // environment. Bump TRACK_BLOCK_LIGHT toward 15 to brighten, drop to 1 for near-pitch-black.
    int light = LightTexture.pack(TRACK_BLOCK_LIGHT, TRACK_SKY_LIGHT);
    int overlay = OverlayTexture.NO_OVERLAY;
    float camX = (float) cam.x;
    float camY = (float) cam.y;
    float camZ = (float) cam.z;

    int N = CROSS_SECTION_VERTS;
    int vertsPerSample = RAIL_COUNT * N;

    // ---- Rails + spine (3 continuous tubes along the path) ----
    // Skip the launch-tunnel section (samples 0..RENDER_START_SAMPLE-1) — the rider only sees
    // the open dome track from ~40 s onward.
    int firstSample = Math.min(RENDER_START_SAMPLE, sampleCount - 1);
    for (int i = firstSample; i < sampleCount - 1; i++) {
      // Hide the track section inside the launch-tunnel cylinder (see insideCyl).
      if (insideCyl.length == sampleCount && (insideCyl[i] || insideCyl[i + 1])) continue;
      int baseA = i * vertsPerSample;
      int baseB = (i + 1) * vertsPerSample;
      for (int rail = 0; rail < RAIL_COUNT; rail++) {
        int aA = baseA + rail * N;
        int aB = baseB + rail * N;
        emitTubeSegment(
            vc, pose, vertX, vertY, vertZ, aA, aB, camX, camY, camZ, light, overlay, COLOR_R,
            COLOR_G, COLOR_B);
      }
    }

    // ---- Triangular bracing struts ----
    for (int s = 0; s < strutCount; s++) {
      int aA = s * 2 * N;
      int aB = aA + N;
      emitTubeSegment(
          vc,
          pose,
          strutVertX,
          strutVertY,
          strutVertZ,
          aA,
          aB,
          camX,
          camY,
          camZ,
          light,
          overlay,
          COLOR_R,
          COLOR_G,
          COLOR_B);
    }

    bufferSource.endBatch(renderType);
  }

  /**
   * Build two cross-section rings (start, end) for a strut between two world-space points. The
   * cross-section is generated in a plane perpendicular to the strut axis, so the tube reads as a
   * proper round bar regardless of strut orientation. Returns false if the start and end coincide
   * (degenerate strut, skip).
   */
  private static boolean writeStrut(
      double[] outX,
      double[] outY,
      double[] outZ,
      int strutIdx,
      double startX,
      double startY,
      double startZ,
      double endX,
      double endY,
      double endZ,
      float radius,
      double[] cosA,
      double[] sinA) {
    double dx = endX - startX;
    double dy = endY - startY;
    double dz = endZ - startZ;
    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (len < 1e-6) return false;
    double ax = dx / len, ay = dy / len, az = dz / len;
    double upX = 0, upY = 1, upZ = 0;
    double dot = upX * ax + upY * ay + upZ * az;
    double uX = upX - dot * ax, uY = upY - dot * ay, uZ = upZ - dot * az;
    double uLen = Math.sqrt(uX * uX + uY * uY + uZ * uZ);
    if (uLen < 1e-6) {
      upX = 1;
      upY = 0;
      upZ = 0;
      dot = upX * ax + upY * ay + upZ * az;
      uX = upX - dot * ax;
      uY = upY - dot * ay;
      uZ = upZ - dot * az;
      uLen = Math.sqrt(uX * uX + uY * uY + uZ * uZ);
    }
    uX /= uLen;
    uY /= uLen;
    uZ /= uLen;
    double vX = ay * uZ - az * uY;
    double vY = az * uX - ax * uZ;
    double vZ = ax * uY - ay * uX;
    int base = strutIdx * 2 * CROSS_SECTION_VERTS;
    for (int k = 0; k < CROSS_SECTION_VERTS; k++) {
      double offX = radius * (cosA[k] * uX + sinA[k] * vX);
      double offY = radius * (cosA[k] * uY + sinA[k] * vY);
      double offZ = radius * (cosA[k] * uZ + sinA[k] * vZ);
      outX[base + k] = startX + offX;
      outY[base + k] = startY + offY;
      outZ[base + k] = startZ + offZ;
      outX[base + CROSS_SECTION_VERTS + k] = endX + offX;
      outY[base + CROSS_SECTION_VERTS + k] = endY + offY;
      outZ[base + CROSS_SECTION_VERTS + k] = endZ + offZ;
    }
    return true;
  }

  /** Emit one tube segment (N quads connecting two cross-section rings of N verts each). */
  private static void emitTubeSegment(
      VertexConsumer vc,
      PoseStack.Pose pose,
      double[] vx,
      double[] vy,
      double[] vz,
      int baseStart,
      int baseEnd,
      float camX,
      float camY,
      float camZ,
      int light,
      int overlay,
      float cr,
      float cg,
      float cb) {
    int N = CROSS_SECTION_VERTS;
    for (int k = 0; k < N; k++) {
      int kn = (k + 1) % N;
      float a0x = (float) (vx[baseStart + k] - camX);
      float a0y = (float) (vy[baseStart + k] - camY);
      float a0z = (float) (vz[baseStart + k] - camZ);
      float a1x = (float) (vx[baseStart + kn] - camX);
      float a1y = (float) (vy[baseStart + kn] - camY);
      float a1z = (float) (vz[baseStart + kn] - camZ);
      float b1x = (float) (vx[baseEnd + kn] - camX);
      float b1y = (float) (vy[baseEnd + kn] - camY);
      float b1z = (float) (vz[baseEnd + kn] - camZ);
      float b0x = (float) (vx[baseEnd + k] - camX);
      float b0y = (float) (vy[baseEnd + k] - camY);
      float b0z = (float) (vz[baseEnd + k] - camZ);
      float u0 = (float) k / N;
      float u1 = (float) (k + 1) / N;
      addVertex(vc, pose, a0x, a0y, a0z, u0, 0f, light, overlay, cr, cg, cb);
      addVertex(vc, pose, b0x, b0y, b0z, u0, 1f, light, overlay, cr, cg, cb);
      addVertex(vc, pose, b1x, b1y, b1z, u1, 1f, light, overlay, cr, cg, cb);
      addVertex(vc, pose, a1x, a1y, a1z, u1, 0f, light, overlay, cr, cg, cb);
    }
  }

  private static void addVertex(
      VertexConsumer vc,
      PoseStack.Pose pose,
      float x,
      float y,
      float z,
      float u,
      float v,
      int light,
      int overlay,
      float r,
      float g,
      float b) {
    vc.addVertex(pose, x, y, z)
        .setColor(r, g, b, COLOR_A)
        .setUv(u, v)
        .setOverlay(overlay)
        .setLight(light)
        .setNormal(pose, 0f, 1f, 0f);
  }
}
