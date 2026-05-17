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
import org.joml.Vector3f;

/**
 * Hyperspace streak effect for Hyperspace Mountain. Renders a static cloud of long, thin emissive
 * blue ribbons inside the launch tunnel volume during the elapsed-seconds window where the server
 * runs its wool/concrete-powder flash. The rider's motion through the static field is what produces
 * the apparent warp / tunnel-vision effect — same illusion as the IRL ride.
 *
 * <p>Each streak is an axis-aligned billboard: long axis fixed to world +Z (tunnel direction);
 * short axis rotates per-frame to face the camera. The texture has a blue-white core fading to
 * transparent at the ends, so streaks read as light trails rather than hard quads.
 */
public final class SpaceMountainHyperspaceRenderer {
  private static final Identifier STREAK_TEXTURE =
      Identifier.fromNamespaceAndPath("imaginemorefun", "textures/particle/hyperspace_streak.png");

  // Tunnel volume — matches the bbox of cells the server animates during 40-55 s elapsed.
  private static final double TUNNEL_X_MIN = -297, TUNNEL_X_MAX = -253;
  private static final double TUNNEL_Y_MIN = 63, TUNNEL_Y_MAX = 88;
  private static final double TUNNEL_Z_MIN = 126, TUNNEL_Z_MAX = 200;
  private static final double TUNNEL_CENTER_X = (TUNNEL_X_MIN + TUNNEL_X_MAX) * 0.5;
  private static final double TUNNEL_CENTER_Y = (TUNNEL_Y_MIN + TUNNEL_Y_MAX) * 0.5;

  // Active window — matches the captured server animation timing.
  private static final int START_SECONDS = 40;
  private static final int END_SECONDS = 55;

  private static final int STREAK_COUNT = 600;
  private static final float STREAK_LENGTH_MIN = 1.5f;
  private static final float STREAK_LENGTH_MAX = 4.5f;
  private static final float STREAK_WIDTH = 0.05f;
  private static final long SEED = 0xD15CE5L;

  private static double[] streakX = new double[0];
  private static double[] streakY = new double[0];
  private static double[] streakZ = new double[0];
  private static float[] streakHalfLen = new float[0];

  static {
    generateStreaks();
  }

  private SpaceMountainHyperspaceRenderer() {}

  public static void register() {
    WorldRenderEvents.AFTER_ENTITIES.register(SpaceMountainHyperspaceRenderer::render);
  }

  private static void generateStreaks() {
    Random rng = new Random(SEED);
    streakX = new double[STREAK_COUNT];
    streakY = new double[STREAK_COUNT];
    streakZ = new double[STREAK_COUNT];
    streakHalfLen = new float[STREAK_COUNT];
    for (int i = 0; i < STREAK_COUNT; i++) {
      // Bias the distribution toward the tunnel walls. Pick a random angle around the tunnel
      // axis; pick a power-distributed radius that pushes points outward; map to (X, Y).
      double theta = rng.nextDouble() * Math.PI * 2;
      double rNorm = Math.pow(rng.nextDouble(), 0.4);
      double halfRangeX = (TUNNEL_X_MAX - TUNNEL_X_MIN) * 0.5;
      double halfRangeY = (TUNNEL_Y_MAX - TUNNEL_Y_MIN) * 0.5;
      streakX[i] = TUNNEL_CENTER_X + Math.cos(theta) * halfRangeX * rNorm;
      streakY[i] = TUNNEL_CENTER_Y + Math.sin(theta) * halfRangeY * rNorm;
      streakZ[i] = TUNNEL_Z_MIN + rng.nextDouble() * (TUNNEL_Z_MAX - TUNNEL_Z_MIN);
      float scale = STREAK_LENGTH_MIN + (STREAK_LENGTH_MAX - STREAK_LENGTH_MIN) * rng.nextFloat();
      streakHalfLen[i] = scale * 0.5f;
    }
    NotRidingAlertClient.LOGGER.info(
        "[SpaceMountainHyperspaceRenderer] generated {} streaks in tunnel bbox", STREAK_COUNT);
  }

  private static boolean inWindow() {
    if (!SpaceMountainOverride.isActive()) return false;
    Integer elapsed = CurrentRideHolder.getElapsedSeconds();
    return elapsed != null && elapsed >= START_SECONDS && elapsed <= END_SECONDS;
  }

  private static void render(WorldRenderContext ctx) {
    if (!inWindow()) return;
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null) return;
    drawStreaks(ctx, mc);
  }

  private static void drawStreaks(WorldRenderContext ctx, Minecraft mc) {
    Camera camera = mc.gameRenderer.getMainCamera();
    Vec3 cam = camera.position();

    PoseStack poseStack = ctx.matrices();
    PoseStack.Pose pose = poseStack.last();
    BufferSource bufferSource = mc.renderBuffers().bufferSource();
    RenderType renderType = RenderTypes.eyes(STREAK_TEXTURE);
    VertexConsumer vc = bufferSource.getBuffer(renderType);

    int light = LightTexture.FULL_BRIGHT;
    int overlay = OverlayTexture.NO_OVERLAY;
    float camX = (float) cam.x;
    float camY = (float) cam.y;
    float camZ = (float) cam.z;

    Vector3f longAxis = new Vector3f(0f, 0f, 1f);
    Vector3f toCam = new Vector3f();
    Vector3f cross = new Vector3f();
    Vector3f widthAxis = new Vector3f();

    for (int i = 0; i < streakX.length; i++) {
      float cx = (float) (streakX[i] - camX);
      float cy = (float) (streakY[i] - camY);
      float cz = (float) (streakZ[i] - camZ);

      // toCam = camera - streakPos (in camera-local coords this is just -center)
      toCam.set(-cx, -cy, -cz);
      longAxis.cross(toCam, cross);
      float crossLen = cross.length();
      if (crossLen < 1e-4f) {
        widthAxis.set(1f, 0f, 0f);
      } else {
        widthAxis.set(cross).div(crossLen);
      }

      float halfLen = streakHalfLen[i];
      float wx = widthAxis.x * STREAK_WIDTH * 0.5f;
      float wy = widthAxis.y * STREAK_WIDTH * 0.5f;
      float wz = widthAxis.z * STREAK_WIDTH * 0.5f;

      // 4 corners of the ribbon — long axis along ±Z, width along ±widthAxis.
      addVertex(vc, pose, cx - wx, cy - wy, cz - wz - halfLen, 0f, 0f, light, overlay);
      addVertex(vc, pose, cx + wx, cy + wy, cz + wz - halfLen, 1f, 0f, light, overlay);
      addVertex(vc, pose, cx + wx, cy + wy, cz + wz + halfLen, 1f, 1f, light, overlay);
      addVertex(vc, pose, cx - wx, cy - wy, cz - wz + halfLen, 0f, 1f, light, overlay);
    }
    bufferSource.endBatch(renderType);
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
      int overlay) {
    vc.addVertex(pose, x, y, z)
        .setColor(1f, 1f, 1f, 1f)
        .setUv(u, v)
        .setOverlay(overlay)
        .setLight(light)
        .setNormal(pose, 0f, 1f, 0f);
  }
}
