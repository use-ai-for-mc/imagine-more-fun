package com.chenweikeng.imf.nra.debug;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.joml.Vector3f;

/**
 * Opt-in, bounded capture of {@code minecraft:dust} parameters before client-side color
 * randomization.
 *
 * <p>This is deliberately disabled after every client start. It is controlled through DebugBridge
 * so particle investigations do not need a user-facing command or persisted configuration.
 */
public final class DustParticlePacketCapture {
  private static final double MIN_RADIUS = 0.5;
  private static final double MAX_RADIUS = 64.0;
  private static final int MAX_CAPTURE_LIMIT = 512;
  private static final int MAX_DURATION_SECONDS = 300;

  private static final List<Capture> CAPTURES = new ArrayList<>();

  private static volatile boolean enabled;
  private static double radius = 8.0;
  private static double radiusSquared = radius * radius;
  private static int captureLimit = 128;
  private static long startedAtMillis;
  private static long deadlineNanos;

  private DustParticlePacketCapture() {}

  /** Starts a 30-second, 8-block capture retaining at most 128 dust packets. */
  public static String start() {
    return start(8.0, 128, 30);
  }

  /** Starts a fresh capture and discards samples from the previous session. */
  public static synchronized String start(
      double requestedRadius, int requestedLimit, int requestedDurationSeconds) {
    radius = clamp(requestedRadius, MIN_RADIUS, MAX_RADIUS);
    radiusSquared = radius * radius;
    captureLimit = Math.max(1, Math.min(requestedLimit, MAX_CAPTURE_LIMIT));
    int durationSeconds = Math.max(1, Math.min(requestedDurationSeconds, MAX_DURATION_SECONDS));
    startedAtMillis = System.currentTimeMillis();
    deadlineNanos = System.nanoTime() + durationSeconds * 1_000_000_000L;
    CAPTURES.clear();
    enabled = true;
    return describeLocked();
  }

  /** Stops capture without clearing its samples. */
  public static synchronized String stop() {
    enabled = false;
    return describeLocked();
  }

  /** Clears all captured packets and leaves capture disabled. */
  public static synchronized String clear() {
    enabled = false;
    CAPTURES.clear();
    return describeLocked();
  }

  /** Returns capture status for quick DebugBridge health checks. */
  public static synchronized String describe() {
    expireIfNeededLocked(System.nanoTime());
    return describeLocked();
  }

  /** Returns a detached, serialization-friendly copy for DebugBridge. */
  public static synchronized List<Map<String, Object>> snapshot() {
    expireIfNeededLocked(System.nanoTime());
    List<Map<String, Object>> result = new ArrayList<>(CAPTURES.size());
    for (Capture capture : CAPTURES) {
      result.add(capture.toMap());
    }
    return result;
  }

  /** Called from the packet-listener mixin before Minecraft expands a server packet. */
  public static void capture(ClientboundLevelParticlesPacket packet) {
    if (!enabled || !(packet.getParticle() instanceof DustParticleOptions dust)) {
      return;
    }

    long nowNanos = System.nanoTime();
    if (nowNanos >= deadlineNanos) {
      synchronized (DustParticlePacketCapture.class) {
        expireIfNeededLocked(nowNanos);
      }
      return;
    }

    Minecraft client = Minecraft.getInstance();
    if (client.player == null) {
      return;
    }

    double dx = packet.getX() - client.player.getX();
    double dy = packet.getY() - client.player.getY();
    double dz = packet.getZ() - client.player.getZ();
    double distanceSquared = dx * dx + dy * dy + dz * dz;
    if (distanceSquared > radiusSquared) {
      return;
    }

    Vector3f color = dust.getColor();
    int red = channelToByte(color.x());
    int green = channelToByte(color.y());
    int blue = channelToByte(color.z());
    int rgb = (red << 16) | (green << 8) | blue;
    Capture capture =
        new Capture(
            "server_packet",
            System.currentTimeMillis(),
            packet.getX(),
            packet.getY(),
            packet.getZ(),
            Math.sqrt(distanceSquared),
            rgb,
            red,
            green,
            blue,
            dust.getScale(),
            packet.getCount(),
            packet.getXDist(),
            packet.getYDist(),
            packet.getZDist(),
            packet.getMaxSpeed(),
            packet.isOverrideLimiter(),
            packet.alwaysShow());

    add(capture, nowNanos);
  }

  /** Called for every concrete particle creation, including client-generated effects. */
  public static void captureCreated(
      ParticleOptions options,
      double x,
      double y,
      double z,
      double xVelocity,
      double yVelocity,
      double zVelocity) {
    if (!enabled || !(options instanceof DustParticleOptions dust)) {
      return;
    }

    long nowNanos = System.nanoTime();
    if (nowNanos >= deadlineNanos) {
      synchronized (DustParticlePacketCapture.class) {
        expireIfNeededLocked(nowNanos);
      }
      return;
    }

    Minecraft client = Minecraft.getInstance();
    if (client.player == null) {
      return;
    }

    double dx = x - client.player.getX();
    double dy = y - client.player.getY();
    double dz = z - client.player.getZ();
    double distanceSquared = dx * dx + dy * dy + dz * dz;
    if (distanceSquared > radiusSquared) {
      return;
    }

    Vector3f color = dust.getColor();
    int red = channelToByte(color.x());
    int green = channelToByte(color.y());
    int blue = channelToByte(color.z());
    int rgb = (red << 16) | (green << 8) | blue;
    Capture capture =
        new Capture(
            "particle_engine",
            System.currentTimeMillis(),
            x,
            y,
            z,
            Math.sqrt(distanceSquared),
            rgb,
            red,
            green,
            blue,
            dust.getScale(),
            -1,
            (float) xVelocity,
            (float) yVelocity,
            (float) zVelocity,
            0.0F,
            false,
            false);

    add(capture, nowNanos);
  }

  private static void add(Capture capture, long nowNanos) {
    synchronized (DustParticlePacketCapture.class) {
      if (!enabled || nowNanos >= deadlineNanos || CAPTURES.size() >= captureLimit) {
        expireIfNeededLocked(nowNanos);
        return;
      }
      CAPTURES.add(capture);
      if (CAPTURES.size() >= captureLimit) {
        enabled = false;
      }
    }
  }

  private static void expireIfNeededLocked(long nowNanos) {
    if (enabled && nowNanos >= deadlineNanos) {
      enabled = false;
    }
  }

  private static String describeLocked() {
    long remainingMillis =
        enabled ? Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L) : 0L;
    return String.format(
        Locale.ROOT,
        "DustParticlePacketCapture{enabled=%s, samples=%d/%d, radius=%.2f, remainingMs=%d}",
        enabled,
        CAPTURES.size(),
        captureLimit,
        radius,
        remainingMillis);
  }

  private static int channelToByte(float channel) {
    return Math.max(0, Math.min(255, Math.round(channel * 255.0F)));
  }

  private static double clamp(double value, double min, double max) {
    if (!Double.isFinite(value)) {
      return min;
    }
    return Math.max(min, Math.min(max, value));
  }

  private record Capture(
      String source,
      long receivedAtMillis,
      double x,
      double y,
      double z,
      double playerDistance,
      int rgb,
      int red,
      int green,
      int blue,
      float scale,
      int count,
      float xSpread,
      float ySpread,
      float zSpread,
      float maxSpeed,
      boolean overrideLimiter,
      boolean alwaysShow) {
    private Map<String, Object> toMap() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("type", "minecraft:dust");
      result.put("source", source);
      result.put("receivedAtMillis", receivedAtMillis);
      result.put("sinceStartMillis", Math.max(0L, receivedAtMillis - startedAtMillis));
      result.put("x", x);
      result.put("y", y);
      result.put("z", z);
      result.put("playerDistance", playerDistance);
      result.put("colorHex", String.format(Locale.ROOT, "#%06X", rgb));
      result.put("red", red);
      result.put("green", green);
      result.put("blue", blue);
      result.put("scale", scale);
      if ("server_packet".equals(source)) {
        result.put("count", count);
        result.put("xSpread", xSpread);
        result.put("ySpread", ySpread);
        result.put("zSpread", zSpread);
        result.put("maxSpeed", maxSpeed);
        result.put("overrideLimiter", overrideLimiter);
        result.put("alwaysShow", alwaysShow);
      } else {
        result.put("xVelocity", xSpread);
        result.put("yVelocity", ySpread);
        result.put("zVelocity", zSpread);
      }
      return result;
    }
  }
}
