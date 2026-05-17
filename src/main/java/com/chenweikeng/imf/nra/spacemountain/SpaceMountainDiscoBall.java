package com.chenweikeng.imf.nra.spacemountain;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Emulates disco-ball star projectors. Each ball is a point that emits {@link #BEAMS_PER_BALL}
 * beams spread over a {@code 2 x CONE_HALF_ANGLE_DEG} cone; every beam raycasts outward and the
 * nearest surface it hits — a world block ({@link net.minecraft.world.level.Level#clip}) or the STL
 * mesh ({@link SpaceMountainStlBvh}), whichever is closer — gets a star dot. Spinning a ball sweeps
 * its beams, so the dots drag across the surfaces.
 *
 * <p>Experimental: balls are registered at runtime over the debug bridge — stand where the ball
 * goes, face the aim, and {@code addBall} with your position + yaw/pitch.
 */
public final class SpaceMountainDiscoBall {
  private static final int BEAMS_PER_BALL = 800;
  private static final double CONE_HALF_ANGLE_DEG = 120.0; // 240-degree full cone
  private static final double MAX_BEAM = 128.0; // max beam length, blocks
  private static final double AUTO_SPIN_INTERVAL_SEC = 20.0; // auto-spin hand-off period
  private static final float DOT_HALF_SIZE = 0.13f;

  private static final Identifier DOT_TEXTURE =
      Identifier.fromNamespaceAndPath("imaginemorefun", "textures/particle/star.png");

  // Balls persist here — saved on every change, loaded at startup.
  private static final Path PERSIST_PATH =
      FabricLoader.getInstance()
          .getConfigDir()
          .resolve("imaginemorefun")
          .resolve("disco_balls.json");

  // Cells the prismarine "cover" marks star-free — a star landing in one is dropped. Baked from
  // an /imf dumpchunks capture by debug-dumps/bake-disco-exclusion.py; world-coordinate triples.
  private static final Path EXCLUSION_PATH =
      FabricLoader.getInstance()
          .getConfigDir()
          .resolve("imaginemorefun")
          .resolve("disco_exclusion.json");
  private static final LongOpenHashSet exclusionCells = new LongOpenHashSet();

  // Beam directions in the cone's local frame (around local +Z), shared by every ball.
  private static final double[] BEAM_LOCAL = new double[BEAMS_PER_BALL * 3];

  static {
    double capCos = Math.cos(Math.toRadians(CONE_HALF_ANGLE_DEG));
    double golden = Math.PI * (3.0 - Math.sqrt(5.0));
    for (int i = 0; i < BEAMS_PER_BALL; i++) {
      // Uniform in cos(theta) over [capCos, 1] => equal-area coverage of the cap.
      double cosT = 1.0 - (i + 0.5) / BEAMS_PER_BALL * (1.0 - capCos);
      double sinT = Math.sqrt(Math.max(0.0, 1.0 - cosT * cosT));
      double phi = i * golden;
      BEAM_LOCAL[i * 3] = sinT * Math.cos(phi);
      BEAM_LOCAL[i * 3 + 1] = sinT * Math.sin(phi);
      BEAM_LOCAL[i * 3 + 2] = cosT;
    }
  }

  /** One projector: a fixed position, an aim, a spin state, and its cached projected dots. */
  private static final class Ball {
    double x, y, z;
    double aimYaw, aimPitch; // cone-axis orientation, MC degrees
    double spinDeg; // current rotation about world Y
    double spinRate; // degrees per second (0 = static)
    double closeRadius = 6.0; // blocks; what counts as "super close" to the ball
    int maxCloseDots = -1; // cap on dots within closeRadius; -1 = no cap
    double[] dots = new double[0]; // 3 doubles per hit dot
    int dotCount;
    boolean dirty = true;
  }

  private static final CopyOnWriteArrayList<Ball> balls = new CopyOnWriteArrayList<>();
  private static volatile boolean ENABLED = true;

  private static SpaceMountainStlBvh bvh;
  private static boolean bvhTried;
  private static long lastNanos;

  // Auto-spin: every AUTO_SPIN_INTERVAL_SEC the spin hands off to a random ball, never a repeat.
  private static volatile boolean autoSpinEnabled;
  private static volatile double autoSpinRate = 10.0;
  private static double autoSpinTimer;
  private static int autoSpinCurrent = -1;
  private static final java.util.Random autoSpinRng = new java.util.Random();

  // Fraction of beams that raycast the STL; the rest ignore it and pass through to the walls.
  private static volatile double stlBeamFraction = 0.5;

  private SpaceMountainDiscoBall() {}

  public static void register() {
    load();
    loadExclusion();
    WorldRenderEvents.AFTER_ENTITIES.register(SpaceMountainDiscoBall::render);
  }

  // --- Bridge API -----------------------------------------------------------

  /** Register a projector at (x,y,z) aimed along (yaw,pitch). Returns the new ball count. */
  public static int addBall(double x, double y, double z, double yaw, double pitch) {
    Ball b = new Ball();
    b.x = x;
    b.y = y;
    b.z = z;
    b.aimYaw = yaw;
    b.aimPitch = pitch;
    balls.add(b);
    NotRidingAlertClient.LOGGER.info(
        "[SpaceMountainDiscoBall] added ball {} at ({}, {}, {}) aim=({}, {})",
        balls.size() - 1,
        x,
        y,
        z,
        yaw,
        pitch);
    save();
    return balls.size();
  }

  public static void clearBalls() {
    balls.clear();
    NotRidingAlertClient.LOGGER.info("[SpaceMountainDiscoBall] cleared all balls");
    save();
  }

  /** Set spin rate (deg/sec) for ball {@code index}, or for all balls when index &lt; 0. */
  public static void setSpin(int index, double degPerSec) {
    if (index < 0) {
      for (Ball b : balls) b.spinRate = degPerSec;
    } else if (index < balls.size()) {
      balls.get(index).spinRate = degPerSec;
    }
    NotRidingAlertClient.LOGGER.info(
        "[SpaceMountainDiscoBall] setSpin index={} rate={}", index, degPerSec);
    save();
  }

  /**
   * Enable/disable auto-spin: every {@link #AUTO_SPIN_INTERVAL_SEC} seconds the spin hands off to a
   * random ball — never the one that just had it — so exactly one ball spins at a time.
   */
  public static void setAutoSpin(boolean enabled, double degPerSec) {
    autoSpinEnabled = enabled;
    autoSpinRate = degPerSec;
    autoSpinCurrent = -1;
    autoSpinTimer = 0.0;
    if (!enabled) {
      for (Ball b : balls) b.spinRate = 0.0;
    }
    NotRidingAlertClient.LOGGER.info(
        "[SpaceMountainDiscoBall] setAutoSpin enabled={} rate={}", enabled, degPerSec);
    save();
  }

  /**
   * Cap how many star dots may land within {@code closeRadius} blocks of the ball — the closest
   * beams beyond {@code maxDots} are dropped, clearing the cluster right at the ball. {@code
   * maxDots < 0} removes the cap. {@code index < 0} applies to all balls.
   */
  public static void setCloseLimit(int index, double closeRadius, int maxDots) {
    if (index < 0) {
      for (Ball b : balls) {
        b.closeRadius = closeRadius;
        b.maxCloseDots = maxDots;
        b.dirty = true;
      }
    } else if (index < balls.size()) {
      Ball b = balls.get(index);
      b.closeRadius = closeRadius;
      b.maxCloseDots = maxDots;
      b.dirty = true;
    }
    NotRidingAlertClient.LOGGER.info(
        "[SpaceMountainDiscoBall] setCloseLimit index={} closeRadius={} maxDots={}",
        index,
        closeRadius,
        maxDots);
    save();
  }

  /**
   * Set the fraction (0..1) of each ball's beams that raycast the STL. The rest ignore the STL and
   * pass straight through to the world blocks behind it — fewer stars on the STL, more on the
   * walls.
   */
  public static void setStlBeamFraction(double fraction) {
    stlBeamFraction = Math.max(0.0, Math.min(1.0, fraction));
    for (Ball b : balls) b.dirty = true;
    NotRidingAlertClient.LOGGER.info(
        "[SpaceMountainDiscoBall] setStlBeamFraction {}", stlBeamFraction);
    save();
  }

  /** Force every ball to re-project on the next frame (e.g. after the world changed). */
  public static void reproject() {
    for (Ball b : balls) b.dirty = true;
  }

  public static void setEnabled(boolean enabled) {
    ENABLED = enabled;
    NotRidingAlertClient.LOGGER.info("[SpaceMountainDiscoBall] enabled={}", enabled);
  }

  public static boolean isEnabled() {
    return ENABLED;
  }

  public static String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append("balls=").append(balls.size()).append(" enabled=").append(ENABLED);
    sb.append(" autoSpin=").append(autoSpinEnabled).append(" rate=").append(autoSpinRate);
    sb.append(" stlBeamFraction=").append(stlBeamFraction);
    sb.append(" exclusionCells=").append(exclusionCells.size());
    int i = 0;
    for (Ball b : balls) {
      sb.append(
          String.format(
              "  [%d] pos=(%.1f,%.1f,%.1f) aim=(%.0f,%.0f) spin=%.0f rate=%.0f dots=%d",
              i++, b.x, b.y, b.z, b.aimYaw, b.aimPitch, b.spinDeg, b.spinRate, b.dotCount));
    }
    return sb.toString();
  }

  // --- Persistence ----------------------------------------------------------

  /** Load balls from {@link #PERSIST_PATH}, replacing any currently registered. */
  public static void load() {
    balls.clear();
    if (!Files.exists(PERSIST_PATH)) return;
    try {
      JsonObject root = JsonParser.parseString(Files.readString(PERSIST_PATH)).getAsJsonObject();
      for (JsonElement el : root.getAsJsonArray("balls")) {
        JsonObject o = el.getAsJsonObject();
        Ball b = new Ball();
        b.x = o.get("x").getAsDouble();
        b.y = o.get("y").getAsDouble();
        b.z = o.get("z").getAsDouble();
        b.aimYaw = o.get("aimYaw").getAsDouble();
        b.aimPitch = o.get("aimPitch").getAsDouble();
        if (o.has("spinDeg")) b.spinDeg = o.get("spinDeg").getAsDouble();
        if (o.has("spinRate")) b.spinRate = o.get("spinRate").getAsDouble();
        if (o.has("closeRadius")) b.closeRadius = o.get("closeRadius").getAsDouble();
        if (o.has("maxCloseDots")) b.maxCloseDots = o.get("maxCloseDots").getAsInt();
        balls.add(b);
      }
      if (root.has("autoSpinEnabled")) {
        autoSpinEnabled = root.get("autoSpinEnabled").getAsBoolean();
      }
      if (root.has("autoSpinRate")) autoSpinRate = root.get("autoSpinRate").getAsDouble();
      if (root.has("stlBeamFraction")) {
        stlBeamFraction = root.get("stlBeamFraction").getAsDouble();
      }
      autoSpinCurrent = -1;
      autoSpinTimer = 0.0;
      NotRidingAlertClient.LOGGER.info(
          "[SpaceMountainDiscoBall] loaded {} balls from {}", balls.size(), PERSIST_PATH);
    } catch (IOException | RuntimeException e) {
      NotRidingAlertClient.LOGGER.error("[SpaceMountainDiscoBall] failed to load balls", e);
    }
  }

  /** Write the current balls to {@link #PERSIST_PATH}; called after every change. */
  private static void save() {
    JsonArray arr = new JsonArray();
    for (Ball b : balls) {
      JsonObject o = new JsonObject();
      o.addProperty("x", b.x);
      o.addProperty("y", b.y);
      o.addProperty("z", b.z);
      o.addProperty("aimYaw", b.aimYaw);
      o.addProperty("aimPitch", b.aimPitch);
      o.addProperty("spinDeg", b.spinDeg);
      o.addProperty("spinRate", b.spinRate);
      o.addProperty("closeRadius", b.closeRadius);
      o.addProperty("maxCloseDots", b.maxCloseDots);
      arr.add(o);
    }
    JsonObject root = new JsonObject();
    root.add("balls", arr);
    root.addProperty("autoSpinEnabled", autoSpinEnabled);
    root.addProperty("autoSpinRate", autoSpinRate);
    root.addProperty("stlBeamFraction", stlBeamFraction);
    try {
      Files.createDirectories(PERSIST_PATH.getParent());
      Files.writeString(PERSIST_PATH, root.toString());
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.error("[SpaceMountainDiscoBall] failed to save balls", e);
    }
  }

  /** Load the prismarine-cover exclusion cells from {@link #EXCLUSION_PATH}. */
  public static void loadExclusion() {
    exclusionCells.clear();
    if (!Files.exists(EXCLUSION_PATH)) return;
    try {
      JsonObject root = JsonParser.parseString(Files.readString(EXCLUSION_PATH)).getAsJsonObject();
      JsonArray cells = root.getAsJsonArray("cells");
      for (int i = 0; i + 2 < cells.size(); i += 3) {
        exclusionCells.add(
            BlockPos.asLong(
                cells.get(i).getAsInt(), cells.get(i + 1).getAsInt(), cells.get(i + 2).getAsInt()));
      }
      NotRidingAlertClient.LOGGER.info(
          "[SpaceMountainDiscoBall] loaded {} exclusion cells from {}",
          exclusionCells.size(),
          EXCLUSION_PATH);
    } catch (IOException | RuntimeException e) {
      NotRidingAlertClient.LOGGER.error(
          "[SpaceMountainDiscoBall] failed to load exclusion cells", e);
    }
  }

  // --- Projection -----------------------------------------------------------

  private static void ensureBvh() {
    if (bvhTried) return;
    bvhTried = true;
    double[] verts = SpaceMountainStlOverlay.getWorldVertices();
    int tc = SpaceMountainStlOverlay.getStlTriangleCount();
    if (tc > 0 && verts.length >= tc * 9) {
      long t0 = System.nanoTime();
      bvh = new SpaceMountainStlBvh(verts, tc);
      NotRidingAlertClient.LOGGER.info(
          "[SpaceMountainDiscoBall] built STL BVH ({} triangles) in {} ms",
          tc,
          (System.nanoTime() - t0) / 1_000_000L);
    } else {
      NotRidingAlertClient.LOGGER.warn(
          "[SpaceMountainDiscoBall] STL not loaded — beams will hit world blocks only");
    }
  }

  /** True when the block cell containing (x,y,z) is under the prismarine cover. */
  private static boolean isExcluded(double x, double y, double z) {
    if (exclusionCells.isEmpty()) return false;
    return exclusionCells.contains(BlockPos.asLong(Mth.floor(x), Mth.floor(y), Mth.floor(z)));
  }

  /** Raycast every beam and cache the hit points. */
  private static void project(Ball b, Minecraft mc) {
    ensureBvh();

    double yaw = Math.toRadians(b.aimYaw);
    double pitch = Math.toRadians(b.aimPitch);
    double cp = Math.cos(pitch);
    double ax = -Math.sin(yaw) * cp;
    double ay = -Math.sin(pitch);
    double az = Math.cos(yaw) * cp;

    // Orthonormal basis (A, U, V) with A the cone axis.
    double refX = 0, refY = 1, refZ = 0;
    if (Math.abs(ay) > 0.99) {
      refX = 1;
      refY = 0;
      refZ = 0;
    }
    double ux = refY * az - refZ * ay;
    double uy = refZ * ax - refX * az;
    double uz = refX * ay - refY * ax;
    double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
    ux /= ul;
    uy /= ul;
    uz /= ul;
    double vx = ay * uz - az * uy;
    double vy = az * ux - ax * uz;
    double vz = ax * uy - ay * ux;

    double s = Math.toRadians(b.spinDeg);
    double cs = Math.cos(s), sn = Math.sin(s);

    // Raycast every beam, collecting hit positions + distances.
    double[] hx = new double[BEAMS_PER_BALL];
    double[] hy = new double[BEAMS_PER_BALL];
    double[] hz = new double[BEAMS_PER_BALL];
    double[] hd = new double[BEAMS_PER_BALL];
    int hits = 0;
    for (int i = 0; i < BEAMS_PER_BALL; i++) {
      double lx = BEAM_LOCAL[i * 3], ly = BEAM_LOCAL[i * 3 + 1], lz = BEAM_LOCAL[i * 3 + 2];
      // local frame -> world (x->U, y->V, z->A)
      double dx = lx * ux + ly * vx + lz * ax;
      double dy = lx * uy + ly * vy + lz * ay;
      double dz = lx * uz + ly * vz + lz * az;
      // spin about world Y
      double rdx = dx * cs + dz * sn;
      double rdz = -dx * sn + dz * cs;
      // Even-dithered subset of beams raycast the STL; the rest ignore it and reach the walls.
      boolean useStl = (long) ((i + 1) * stlBeamFraction) != (long) (i * stlBeamFraction);
      double dist = castBeam(mc, b.x, b.y, b.z, rdx, dy, rdz, useStl);
      if (dist > 0.0 && dist < MAX_BEAM) {
        double px = b.x + rdx * dist;
        double py = b.y + dy * dist;
        double pz = b.z + rdz * dist;
        if (isExcluded(px, py, pz)) continue;
        hx[hits] = px;
        hy[hits] = py;
        hz[hits] = pz;
        hd[hits] = dist;
        hits++;
      }
    }

    // Close-in cap: within closeRadius keep only the farthest maxCloseDots dots, dropping the
    // closer ones (the cluster right at the ball). Dots beyond closeRadius are always kept.
    double dropBelow = -1.0; // dots with distance < this are dropped
    if (b.maxCloseDots >= 0) {
      int closeCount = 0;
      for (int i = 0; i < hits; i++) {
        if (hd[i] < b.closeRadius) closeCount++;
      }
      if (closeCount > b.maxCloseDots) {
        double[] closeDist = new double[closeCount];
        int k = 0;
        for (int i = 0; i < hits; i++) {
          if (hd[i] < b.closeRadius) closeDist[k++] = hd[i];
        }
        java.util.Arrays.sort(closeDist);
        dropBelow = b.maxCloseDots == 0 ? b.closeRadius : closeDist[closeCount - b.maxCloseDots];
      }
    }

    double[] out = new double[hits * 3];
    int kept = 0;
    for (int i = 0; i < hits; i++) {
      if (hd[i] < dropBelow) continue;
      out[kept * 3] = hx[i];
      out[kept * 3 + 1] = hy[i];
      out[kept * 3 + 2] = hz[i];
      kept++;
    }
    b.dots = out;
    b.dotCount = kept;
  }

  /** Nearest surface distance along a unit beam — the block hit, plus the STL hit when useStl. */
  private static double castBeam(
      Minecraft mc,
      double ox,
      double oy,
      double oz,
      double dx,
      double dy,
      double dz,
      boolean useStl) {
    double best = MAX_BEAM;
    if (useStl && bvh != null) {
      double t = bvh.raycast(ox, oy, oz, dx, dy, dz, MAX_BEAM);
      if (t < best) best = t;
    }
    if (mc.level != null) {
      Vec3 from = new Vec3(ox, oy, oz);
      Vec3 to = new Vec3(ox + dx * MAX_BEAM, oy + dy * MAX_BEAM, oz + dz * MAX_BEAM);
      ClipContext ctx =
          new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
      BlockHitResult hit = mc.level.clip(ctx);
      if (hit.getType() == HitResult.Type.BLOCK) {
        double bd = hit.getLocation().distanceTo(from);
        if (bd < best) best = bd;
      }
    }
    return best;
  }

  // --- Rendering ------------------------------------------------------------

  /** Hand the spin off to a random ball, never the one currently spinning. */
  private static void autoSpinPick() {
    int n = balls.size();
    if (n == 0) return;
    int next;
    if (autoSpinCurrent < 0 || n == 1) {
      next = autoSpinRng.nextInt(n);
    } else {
      int r = autoSpinRng.nextInt(n - 1);
      if (r >= autoSpinCurrent) r++;
      next = r;
    }
    for (Ball b : balls) b.spinRate = 0.0;
    balls.get(next).spinRate = autoSpinRate;
    autoSpinCurrent = next;
    NotRidingAlertClient.LOGGER.info("[SpaceMountainDiscoBall] auto-spin -> ball {}", next);
  }

  private static void render(WorldRenderContext ctx) {
    if (!ENABLED || balls.isEmpty()) return;
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null) return;

    long now = System.nanoTime();
    double dt = lastNanos == 0L ? 0.0 : Math.min((now - lastNanos) / 1.0e9, 0.1);
    lastNanos = now;

    if (autoSpinEnabled) {
      autoSpinTimer += dt;
      if (autoSpinCurrent < 0 || autoSpinTimer >= AUTO_SPIN_INTERVAL_SEC) {
        autoSpinTimer = 0.0;
        autoSpinPick();
      }
    }

    for (Ball b : balls) {
      if (b.spinRate != 0.0) {
        b.spinDeg = (b.spinDeg + b.spinRate * dt) % 360.0;
        b.dirty = true;
      }
      if (b.dirty) {
        project(b, mc);
        b.dirty = false;
      }
    }
    drawDots(ctx, mc);
  }

  private static void drawDots(WorldRenderContext ctx, Minecraft mc) {
    Camera camera = mc.gameRenderer.getMainCamera();
    Vec3 cam = camera.position();
    Quaternionf rot = camera.rotation();
    Vector3f right = rot.transform(new Vector3f(1f, 0f, 0f));
    Vector3f up = rot.transform(new Vector3f(0f, 1f, 0f));

    PoseStack.Pose pose = ctx.matrices().last();
    BufferSource buffers = mc.renderBuffers().bufferSource();
    RenderType rt = RenderTypes.eyes(DOT_TEXTURE);
    VertexConsumer vc = buffers.getBuffer(rt);
    int light = LightTexture.FULL_BRIGHT;
    int overlay = OverlayTexture.NO_OVERLAY;
    float camX = (float) cam.x, camY = (float) cam.y, camZ = (float) cam.z;

    for (Ball b : balls) {
      for (int i = 0; i < b.dotCount; i++) {
        quad(
            vc,
            pose,
            right,
            up,
            DOT_HALF_SIZE,
            (float) (b.dots[i * 3] - camX),
            (float) (b.dots[i * 3 + 1] - camY),
            (float) (b.dots[i * 3 + 2] - camZ),
            1f,
            1f,
            1f,
            light,
            overlay);
      }
    }
    buffers.endBatch(rt);
  }

  private static void quad(
      VertexConsumer vc,
      PoseStack.Pose pose,
      Vector3f right,
      Vector3f up,
      float h,
      float wx,
      float wy,
      float wz,
      float r,
      float g,
      float b,
      int light,
      int overlay) {
    float rx = right.x * h, ry = right.y * h, rz = right.z * h;
    float ux = up.x * h, uy = up.y * h, uz = up.z * h;
    dotVertex(vc, pose, wx - rx + ux, wy - ry + uy, wz - rz + uz, 0f, 0f, r, g, b, light, overlay);
    dotVertex(vc, pose, wx - rx - ux, wy - ry - uy, wz - rz - uz, 0f, 1f, r, g, b, light, overlay);
    dotVertex(vc, pose, wx + rx - ux, wy + ry - uy, wz + rz - uz, 1f, 1f, r, g, b, light, overlay);
    dotVertex(vc, pose, wx + rx + ux, wy + ry + uy, wz + rz + uz, 1f, 0f, r, g, b, light, overlay);
  }

  private static void dotVertex(
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
      int light,
      int overlay) {
    vc.addVertex(pose, x, y, z)
        .setColor(r, g, b, 1f)
        .setUv(u, v)
        .setOverlay(overlay)
        .setLight(light)
        .setNormal(pose, 0f, 1f, 0f);
  }
}
