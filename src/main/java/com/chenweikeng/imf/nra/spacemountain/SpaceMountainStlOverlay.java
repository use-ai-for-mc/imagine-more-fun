package com.chenweikeng.imf.nra.spacemountain;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;

/**
 * Architect's Space Mountain support-frame STL rendered as a translucent overlay. Used as a spatial
 * reference (the STL is aligned to world coords via {@code splat/handoff/transform.json}) to
 * identify dome boundaries — there's no temporal correlation with the recorded track, but both live
 * in the same MC world frame, so spatial bounds on the STL transfer directly to track-sample
 * filtering.
 *
 * <p>Default ON — toggle via the debug bridge:
 *
 * <pre>
 *   mcp__mcdev-mcp__mc_execute → java.import("...SpaceMountainStlOverlay"):setEnabled(false)
 * </pre>
 *
 * <p>Optional secondary bbox highlight: set bounds via {@link #setHighlightBounds} and any triangle
 * whose centroid falls inside renders in {@link #HIGHLIGHT_R/G/B/A} instead of the base blue. Lets
 * you iteratively define a region (e.g. "the dome part") and watch it light up in red before
 * committing the bounds to a filter.
 */
public final class SpaceMountainStlOverlay {
  private static final String STL_RESOURCE = "/imaginemorefun/space_mountain.stl";

  // Alignment from splat/handoff/transform.json. Bake into the cached vertices once at startup.
  private static final double SCALE = 0.033884415613920256;
  private static final double OFFSET_X = -255.5;
  private static final double OFFSET_Y = 57.5;
  private static final double OFFSET_Z = 173.5;
  private static final double ROT_X_DEG = -88.0;
  private static final double ROT_Y_DEG = -1.0;
  private static final double ROT_Z_DEG = 93.0;

  // Base color — translucent light blue against the dark dome.
  private static final float COLOR_R = 0.4f;
  private static final float COLOR_G = 0.8f;
  private static final float COLOR_B = 1.0f;
  private static final float COLOR_A = 0.35f;

  // Off — the STL is a build-time alignment reference, not a production element. Toggle on over
  // the debug bridge when checking the transform.
  private static volatile boolean ENABLED = false;

  /**
   * Labeled region with a custom color. Triangle whose centroid falls inside renders in (r,g,b,a)
   * instead of the base blue. First match wins (iteration order = JSON order).
   */
  public record Region(
      String name,
      float r,
      float g,
      float b,
      float a,
      double minX,
      double minY,
      double minZ,
      double maxX,
      double maxY,
      double maxZ) {
    public boolean contains(double x, double y, double z) {
      return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
  }

  // Path the Python sidecar writes to; loaded at startup and on demand via reloadRegions().
  private static final Path REGIONS_PATH =
      FabricLoader.getInstance()
          .getConfigDir()
          .resolve("imaginemorefun")
          .resolve("stl_regions.json");

  private static volatile List<Region> regions = List.of();

  // Cache: 3 vertices × 3 doubles per triangle, plus per-triangle centroid for bbox tests.
  private static double[] vertices = new double[0];
  private static double[] centroids = new double[0]; // 3 doubles per triangle
  private static int triangleCount = 0;

  static {
    loadAndTransform();
    reloadRegions();
  }

  private SpaceMountainStlOverlay() {}

  public static void register() {
    WorldRenderEvents.AFTER_ENTITIES.register(SpaceMountainStlOverlay::render);
  }

  public static void setEnabled(boolean enabled) {
    ENABLED = enabled;
    NotRidingAlertClient.LOGGER.info("[SpaceMountainStlOverlay] enabled={}", enabled);
  }

  public static boolean isEnabled() {
    return ENABLED;
  }

  /**
   * Re-read {@link #REGIONS_PATH} from disk. The Python sidecar at {@code
   * debug-dumps/stl-regions.py} writes this file — edit the Python, run it, then call this method
   * via the debug bridge to pick up the new regions without a mod rebuild.
   */
  public static void reloadRegions() {
    try {
      if (!Files.exists(REGIONS_PATH)) {
        regions = List.of();
        NotRidingAlertClient.LOGGER.info(
            "[SpaceMountainStlOverlay] no regions file at {} — overlay rendered in base color",
            REGIONS_PATH);
        return;
      }
      String text = Files.readString(REGIONS_PATH);
      JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
      JsonArray arr = obj.getAsJsonArray("regions");
      List<Region> out = new ArrayList<>();
      for (JsonElement el : arr) {
        JsonObject r = el.getAsJsonObject();
        String name = r.has("name") ? r.get("name").getAsString() : "?";
        JsonArray color = r.getAsJsonArray("color");
        float cr = color.get(0).getAsFloat();
        float cg = color.get(1).getAsFloat();
        float cb = color.get(2).getAsFloat();
        float ca = color.size() > 3 ? color.get(3).getAsFloat() : 0.55f;
        JsonArray b = r.getAsJsonArray("bounds");
        out.add(
            new Region(
                name,
                cr,
                cg,
                cb,
                ca,
                b.get(0).getAsDouble(),
                b.get(1).getAsDouble(),
                b.get(2).getAsDouble(),
                b.get(3).getAsDouble(),
                b.get(4).getAsDouble(),
                b.get(5).getAsDouble()));
      }
      regions = out;
      NotRidingAlertClient.LOGGER.info(
          "[SpaceMountainStlOverlay] loaded {} regions from {}", out.size(), REGIONS_PATH);
    } catch (IOException | RuntimeException ex) {
      NotRidingAlertClient.LOGGER.error("[SpaceMountainStlOverlay] failed to load regions", ex);
    }
  }

  public static List<Region> getRegions() {
    return regions;
  }

  /** World-space STL triangle vertices: 9 doubles per triangle (3 verts x xyz). */
  public static double[] getWorldVertices() {
    return vertices;
  }

  /** Number of STL triangles (0 if the STL failed to load). */
  public static int getStlTriangleCount() {
    return triangleCount;
  }

  /** Quick logger-side report of the cached bbox; handy for "how big is this thing". */
  public static String describe() {
    if (triangleCount == 0) return "STL not loaded";
    double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
    double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
    double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < vertices.length; i += 3) {
      double x = vertices[i], y = vertices[i + 1], z = vertices[i + 2];
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
      if (z < minZ) minZ = z;
      if (z > maxZ) maxZ = z;
    }
    return String.format(
        "%d triangles, bbox X:[%.1f..%.1f] Y:[%.1f..%.1f] Z:[%.1f..%.1f]",
        triangleCount, minX, maxX, minY, maxY, minZ, maxZ);
  }

  private static void loadAndTransform() {
    try (InputStream in = SpaceMountainStlOverlay.class.getResourceAsStream(STL_RESOURCE)) {
      if (in == null) {
        NotRidingAlertClient.LOGGER.error(
            "[SpaceMountainStlOverlay] resource {} not found", STL_RESOURCE);
        return;
      }
      byte[] bytes = in.readAllBytes();
      ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
      buf.position(80);
      int nTri = buf.getInt();
      if ((long) nTri * 50L + 84L > bytes.length) {
        throw new IOException(
            "STL truncated: declared " + nTri + " triangles, file has " + bytes.length + " bytes");
      }

      double rx = Math.toRadians(ROT_X_DEG);
      double ry = Math.toRadians(ROT_Y_DEG);
      double rz = Math.toRadians(ROT_Z_DEG);
      double cx = Math.cos(rx), sx = Math.sin(rx);
      double cy = Math.cos(ry), sy = Math.sin(ry);
      double cz = Math.cos(rz), sz = Math.sin(rz);
      // R = Rx * Ry * Rz expanded.
      double m00 = cy * cz;
      double m01 = -cy * sz;
      double m02 = sy;
      double m10 = cx * sz + sx * sy * cz;
      double m11 = cx * cz - sx * sy * sz;
      double m12 = -sx * cy;
      double m20 = sx * sz - cx * sy * cz;
      double m21 = sx * cz + cx * sy * sz;
      double m22 = cx * cy;

      vertices = new double[nTri * 9];
      centroids = new double[nTri * 3];
      for (int t = 0; t < nTri; t++) {
        buf.position(84 + t * 50 + 12); // skip 12-byte normal
        double cxSum = 0, cySum = 0, czSum = 0;
        for (int v = 0; v < 3; v++) {
          double vx = buf.getFloat() * SCALE;
          double vy = buf.getFloat() * SCALE;
          double vz = buf.getFloat() * SCALE;
          double wx = m00 * vx + m01 * vy + m02 * vz + OFFSET_X;
          double wy = m10 * vx + m11 * vy + m12 * vz + OFFSET_Y;
          double wz = m20 * vx + m21 * vy + m22 * vz + OFFSET_Z;
          int idx = t * 9 + v * 3;
          vertices[idx] = wx;
          vertices[idx + 1] = wy;
          vertices[idx + 2] = wz;
          cxSum += wx;
          cySum += wy;
          czSum += wz;
        }
        centroids[t * 3] = cxSum / 3.0;
        centroids[t * 3 + 1] = cySum / 3.0;
        centroids[t * 3 + 2] = czSum / 3.0;
      }
      triangleCount = nTri;
      NotRidingAlertClient.LOGGER.info(
          "[SpaceMountainStlOverlay] loaded {} triangles ({})", nTri, describe());
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.error("[SpaceMountainStlOverlay] failed to load STL", e);
    }
  }

  private static void render(WorldRenderContext ctx) {
    if (!ENABLED) return;
    if (triangleCount == 0) return;
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null) return;

    Camera camera = mc.gameRenderer.getMainCamera();
    Vec3 cam = camera.position();
    float camX = (float) cam.x;
    float camY = (float) cam.y;
    float camZ = (float) cam.z;

    PoseStack poseStack = ctx.matrices();
    PoseStack.Pose pose = poseStack.last();
    BufferSource bufferSource = mc.renderBuffers().bufferSource();
    RenderType rt = ImfRenderPipelines.translucentTriangles();
    VertexConsumer vc = bufferSource.getBuffer(rt);

    List<Region> rs = regions;
    int rn = rs.size();
    for (int t = 0; t < triangleCount; t++) {
      float r = COLOR_R, g = COLOR_G, b = COLOR_B, a = COLOR_A;
      if (rn > 0) {
        double cx = centroids[t * 3], cy = centroids[t * 3 + 1], cz = centroids[t * 3 + 2];
        for (int i = 0; i < rn; i++) {
          Region rg = rs.get(i);
          if (rg.contains(cx, cy, cz)) {
            r = rg.r;
            g = rg.g;
            b = rg.b;
            a = rg.a;
            break; // first match wins
          }
        }
      }
      int base = t * 9;
      for (int v = 0; v < 3; v++) {
        int idx = base + v * 3;
        vc.addVertex(
                pose,
                (float) (vertices[idx] - camX),
                (float) (vertices[idx + 1] - camY),
                (float) (vertices[idx + 2] - camZ))
            .setColor(r, g, b, a);
      }
    }
    bufferSource.endBatch(rt);
  }
}
