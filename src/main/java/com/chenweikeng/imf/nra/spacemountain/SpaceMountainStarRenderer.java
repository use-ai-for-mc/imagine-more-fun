package com.chenweikeng.imf.nra.spacemountain;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Custom starfield renderer for Space and Hyperspace Mountain. Stars are projected onto the dome's
 * actual interior wall faces — the position list is baked from a chunk dump and shipped as a binary
 * resource ({@code /imaginemorefun/dome_borders.bin}). To regenerate after dome changes: place the
 * player inside the dome, run {@code /imf dumpchunks 6}, then {@code python3
 * debug-dumps/bake-borders.py debug-dumps/chunks-*.bin.gz}.
 *
 * <p><b>Render type.</b> {@link RenderTypes#eyes} uses {@code RenderPipelines#EYES} which is {@code
 * EMISSIVE} (lightmap bypassed, always full-bright), translucent-blended, depth-test on,
 * depth-write off. Stars stay bright in pitch-black show-building interiors; walls in front occlude
 * them; stars don't z-fight each other.
 *
 * <p><b>Layout.</b> Deterministic — partial Fisher-Yates shuffle of the loaded faces with a fixed
 * seed. Same dome → same {@link #STAR_COUNT} stars in the same positions every session. Quad sizes
 * are randomized per star but also seeded.
 */
public final class SpaceMountainStarRenderer {
  private static final Identifier STAR_TEXTURE =
      Identifier.fromNamespaceAndPath("imaginemorefun", "textures/particle/star.png");

  private static final int STAR_COUNT = 3000;
  private static final float STAR_SIZE_MIN = 0.18f;
  private static final float STAR_SIZE_MAX = 0.55f;
  private static final long SEED = 0xCAFEBABEL;

  private static final String BORDERS_RESOURCE = "/imaginemorefun/dome_borders.bin";
  private static final String TRACK_STARS_RESOURCE = "/imaginemorefun/dome_track_stars.bin";
  private static final String STL_STARS_RESOURCE = "/imaginemorefun/stl_stars.bin";

  // Track-surface stars disabled — superseded by STL-surface stars (see appendStlStars).
  // The recorded-track files are kept on disk but no longer used.
  private static final boolean INCLUDE_TRACK_STARS = false;

  // Direction.values() ordinal mapping in 1.21: 0=DOWN, 1=UP, 2=NORTH, 3=SOUTH, 4=WEST, 5=EAST.
  private static final Direction[] DIRECTIONS = {
    Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST,
  };

  private static double[] starX = new double[0];
  private static double[] starY = new double[0];
  private static double[] starZ = new double[0];
  private static float[] starHalfSize = new float[0];

  // Baked dome-wall + STL stars — off by default; the disco-ball projection is the star effect.
  // Re-enable via the bridge: java.import("...SpaceMountainStarRenderer"):setEnabled(true)
  private static volatile boolean ENABLED = false;

  static {
    loadAndPickStars();
  }

  private SpaceMountainStarRenderer() {}

  public static void register() {
    WorldRenderEvents.AFTER_ENTITIES.register(SpaceMountainStarRenderer::render);
  }

  public static void setEnabled(boolean enabled) {
    ENABLED = enabled;
    NotRidingAlertClient.LOGGER.info("[SpaceMountainStarRenderer] enabled={}", enabled);
  }

  public static boolean isEnabled() {
    return ENABLED;
  }

  private static void loadAndPickStars() {
    try (InputStream in = SpaceMountainStarRenderer.class.getResourceAsStream(BORDERS_RESOURCE)) {
      if (in == null) {
        NotRidingAlertClient.LOGGER.error(
            "[SpaceMountainStarRenderer] resource {} not found — no stars will render",
            BORDERS_RESOURCE);
        return;
      }
      DataInputStream dis = new DataInputStream(in);
      byte[] magic = new byte[4];
      dis.readFully(magic);
      if (magic[0] != 'I' || magic[1] != 'F' || magic[2] != 'D' || magic[3] != 'B') {
        throw new IOException("bad magic: " + new String(magic));
      }
      int version = dis.readUnsignedByte();
      if (version != 1) throw new IOException("unsupported borders version: " + version);
      int faceCount = dis.readInt();

      // Read all faces and pre-compute star anchor positions (block center + 0.5 × face normal).
      double[] fx = new double[faceCount];
      double[] fy = new double[faceCount];
      double[] fz = new double[faceCount];
      for (int i = 0; i < faceCount; i++) {
        int x = dis.readInt();
        int y = dis.readInt();
        int z = dis.readInt();
        int dirIdx = dis.readUnsignedByte();
        Direction d = DIRECTIONS[dirIdx];
        fx[i] = x + 0.5 + 0.5 * d.getStepX();
        fy[i] = y + 0.5 + 0.5 * d.getStepY();
        fz[i] = z + 0.5 + 0.5 * d.getStepZ();
      }

      // Partial Fisher-Yates: select STAR_COUNT unique indices deterministically.
      int n = Math.min(STAR_COUNT, faceCount);
      int[] indices = new int[faceCount];
      for (int i = 0; i < faceCount; i++) indices[i] = i;
      Random rng = new Random(SEED);
      starX = new double[n];
      starY = new double[n];
      starZ = new double[n];
      starHalfSize = new float[n];
      for (int i = 0; i < n; i++) {
        int j = i + rng.nextInt(faceCount - i);
        int tmp = indices[i];
        indices[i] = indices[j];
        indices[j] = tmp;
        int pick = indices[i];
        starX[i] = fx[pick];
        starY[i] = fy[pick];
        starZ[i] = fz[pick];
        float scale = STAR_SIZE_MIN + (STAR_SIZE_MAX - STAR_SIZE_MIN) * rng.nextFloat();
        starHalfSize[i] = scale * 0.5f;
      }
      // Append track-surface stars (rails / spine / V-struts). Pre-baked positions, no shuffle —
      // we always include all of them so the look is consistent run-to-run.
      int trackAdded = INCLUDE_TRACK_STARS ? appendTrackStars(rng) : 0;
      // Append STL-surface stars (baked by debug-dumps/bake-stl-stars.py).
      int stlAdded = appendStlStars(rng);
      NotRidingAlertClient.LOGGER.info(
          "[SpaceMountainStarRenderer] loaded {} dome faces ({} dome stars)"
              + " + {} track stars + {} STL stars",
          faceCount,
          n,
          trackAdded,
          stlAdded);
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.error(
          "[SpaceMountainStarRenderer] failed to load borders resource", e);
    }
  }

  /**
   * Read {@link #TRACK_STARS_RESOURCE} (produced by {@code debug-dumps/bake-track-stars.py}) and
   * append each entry to the star arrays. Returns the number appended (0 if the resource is missing
   * — track stars are optional).
   */
  private static int appendTrackStars(Random rng) {
    try (InputStream in =
        SpaceMountainStarRenderer.class.getResourceAsStream(TRACK_STARS_RESOURCE)) {
      if (in == null) return 0;
      DataInputStream dis = new DataInputStream(in);
      byte[] magic = new byte[4];
      dis.readFully(magic);
      if (magic[0] != 'I' || magic[1] != 'F' || magic[2] != 'T' || magic[3] != 'S') {
        throw new IOException("bad track-stars magic: " + new String(magic));
      }
      int version = dis.readUnsignedByte();
      if (version != 1) throw new IOException("unsupported track-stars version: " + version);
      int count = dis.readInt();
      if (count <= 0) return 0;
      int oldLen = starX.length;
      int newLen = oldLen + count;
      starX = java.util.Arrays.copyOf(starX, newLen);
      starY = java.util.Arrays.copyOf(starY, newLen);
      starZ = java.util.Arrays.copyOf(starZ, newLen);
      starHalfSize = java.util.Arrays.copyOf(starHalfSize, newLen);
      for (int i = 0; i < count; i++) {
        starX[oldLen + i] = dis.readDouble();
        starY[oldLen + i] = dis.readDouble();
        starZ[oldLen + i] = dis.readDouble();
        // Slightly smaller stars on track surfaces — they're closer to the camera most of the
        // ride, so a smaller billboard reads as a pinpoint of light rather than a glowing disc.
        float scale =
            STAR_SIZE_MIN * 0.6f + (STAR_SIZE_MAX * 0.6f - STAR_SIZE_MIN * 0.6f) * rng.nextFloat();
        starHalfSize[oldLen + i] = scale * 0.5f;
      }
      return count;
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.error(
          "[SpaceMountainStarRenderer] failed to load track-stars resource", e);
      return 0;
    }
  }

  /**
   * Read {@link #STL_STARS_RESOURCE} (produced by {@code debug-dumps/bake-stl-stars.py}) and append
   * each entry to the star arrays. Returns the number appended (0 if the resource is missing).
   */
  private static int appendStlStars(Random rng) {
    try (InputStream in = SpaceMountainStarRenderer.class.getResourceAsStream(STL_STARS_RESOURCE)) {
      if (in == null) return 0;
      DataInputStream dis = new DataInputStream(in);
      byte[] magic = new byte[4];
      dis.readFully(magic);
      if (magic[0] != 'I' || magic[1] != 'F' || magic[2] != 'S' || magic[3] != 'S') {
        throw new IOException("bad STL-stars magic: " + new String(magic));
      }
      int version = dis.readUnsignedByte();
      if (version != 1) throw new IOException("unsupported STL-stars version: " + version);
      int count = dis.readInt();
      if (count <= 0) return 0;
      int oldLen = starX.length;
      int newLen = oldLen + count;
      starX = java.util.Arrays.copyOf(starX, newLen);
      starY = java.util.Arrays.copyOf(starY, newLen);
      starZ = java.util.Arrays.copyOf(starZ, newLen);
      starHalfSize = java.util.Arrays.copyOf(starHalfSize, newLen);
      for (int i = 0; i < count; i++) {
        starX[oldLen + i] = dis.readDouble();
        starY[oldLen + i] = dis.readDouble();
        starZ[oldLen + i] = dis.readDouble();
        float scale = STAR_SIZE_MIN + (STAR_SIZE_MAX - STAR_SIZE_MIN) * rng.nextFloat();
        starHalfSize[oldLen + i] = scale * 0.5f;
      }
      return count;
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.error(
          "[SpaceMountainStarRenderer] failed to load STL-stars resource", e);
      return 0;
    }
  }

  // Set to true to bypass the ride-active gate — stars render in any world (including SP) so you
  // can fly to the dome coords and inspect their positions. Flip back to false during normal play.
  private static final boolean DEBUG_ALWAYS_RENDER = false;

  private static void render(WorldRenderContext ctx) {
    if (!ENABLED) return;
    if (!DEBUG_ALWAYS_RENDER && !SpaceMountainOverride.isActive()) return;
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null) return;
    if (starX.length == 0) return;
    drawStars(ctx, mc);
  }

  private static void drawStars(WorldRenderContext ctx, Minecraft mc) {
    Camera camera = mc.gameRenderer.getMainCamera();
    Vec3 cam = camera.position();

    Quaternionf rot = camera.rotation();
    Vector3f right = rot.transform(new Vector3f(1f, 0f, 0f));
    Vector3f up = rot.transform(new Vector3f(0f, 1f, 0f));

    PoseStack poseStack = ctx.matrices();
    PoseStack.Pose pose = poseStack.last();
    BufferSource bufferSource = mc.renderBuffers().bufferSource();
    RenderType renderType = RenderTypes.eyes(STAR_TEXTURE);
    VertexConsumer vc = bufferSource.getBuffer(renderType);

    int light = LightTexture.FULL_BRIGHT;
    int overlay = OverlayTexture.NO_OVERLAY;
    float camX = (float) cam.x;
    float camY = (float) cam.y;
    float camZ = (float) cam.z;

    for (int i = 0; i < starX.length; i++) {
      float h = starHalfSize[i];
      float wx = (float) (starX[i] - camX);
      float wy = (float) (starY[i] - camY);
      float wz = (float) (starZ[i] - camZ);

      float rx = right.x * h;
      float ry = right.y * h;
      float rz = right.z * h;
      float ux = up.x * h;
      float uy = up.y * h;
      float uz = up.z * h;

      addVertex(vc, pose, wx - rx + ux, wy - ry + uy, wz - rz + uz, 0f, 0f, light, overlay);
      addVertex(vc, pose, wx - rx - ux, wy - ry - uy, wz - rz - uz, 0f, 1f, light, overlay);
      addVertex(vc, pose, wx + rx - ux, wy + ry - uy, wz + rz - uz, 1f, 1f, light, overlay);
      addVertex(vc, pose, wx + rx + ux, wy + ry + uy, wz + rz + uz, 1f, 0f, light, overlay);
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
