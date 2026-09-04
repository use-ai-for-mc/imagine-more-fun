package com.chenweikeng.imf.nra.quest;

import com.chenweikeng.imf.mixin.NraBossHealthOverlayAccessor;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.pim.tracker.BossBarTracker;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Identifies ImagineFun quest collectibles from their shared entity/particle construction and keeps
 * their visible head model glowing on the client.
 *
 * <p>The server builds trophies, training dummies, honey pots, and similar clickable quest props
 * from an invisible armor stand wearing a custom-model item in its head slot plus an {@link
 * Interaction} at the same base position. A stream of pure-white, unit-scale {@code minecraft:dust}
 * particles surrounds the visible model. Requiring all three signals avoids highlighting ordinary
 * armor-stand decorations or unrelated white dust effects.
 */
public final class QuestCollectibleGlow {
  // Observed live: "Quest: Find 5 Honey Pots (1357.5) ⬅". The first number is a count.
  private static final Pattern DISTANCE_BOSS_BAR =
      Pattern.compile("^Quest: .+(\\([0-9]+(?:\\.[0-9]+)?\\))\\s*[⬅➡⬆⬇↖↗↘↙←→↑↓]?\\s*$");
  private static final int DISTANCE_TEXT_COLOR = 0xC8D6E5;
  private static final double DUST_TO_HEAD_RADIUS = 2.25;
  private static final double DUST_TO_HEAD_RADIUS_SQUARED =
      DUST_TO_HEAD_RADIUS * DUST_TO_HEAD_RADIUS;
  private static final double HEAD_HEIGHT = 1.5;
  private static final double INTERACTION_PAIR_RADIUS = 0.35;
  private static final double INTERACTION_PAIR_RADIUS_SQUARED =
      INTERACTION_PAIR_RADIUS * INTERACTION_PAIR_RADIUS;
  private static final double MARKED_POSITION_EPSILON_SQUARED = 0.01;
  private static final int RENDERED_DUST_GRACE_TICKS = 20;

  // Particle observation, ticks, and render-state collection all run on the client thread.
  private static final Map<UUID, ArmorStand> MARKED = new HashMap<>();
  private static final Map<UUID, Long> LAST_DUST_GAME_TIME = new HashMap<>();
  private static long lastRenderedQuestDustGameTime = Long.MIN_VALUE;
  private static double beamX;
  private static double beamY;
  private static double beamZ;
  private static long pendingDustGameTime = Long.MIN_VALUE;
  private static double pendingDustSumX;
  private static double pendingDustSumY;
  private static double pendingDustSumZ;
  private static int pendingDustCount;

  private QuestCollectibleGlow() {}

  /** Observes a particle before its configured color is randomized for display. */
  public static void observeDust(ParticleOptions options, double x, double y, double z) {
    observeDust(options, x, y, z, false);
  }

  /**
   * Same identification as {@link #observeDust}, but only for particles the client actually
   * spawned.
   */
  public static void observeRenderedDust(ParticleOptions options, double x, double y, double z) {
    observeDust(options, x, y, z, true);
  }

  private static void observeDust(
      ParticleOptions options, double x, double y, double z, boolean rendered) {
    if (!isMatchingDust(options) || !isActive()) {
      return;
    }

    Minecraft client = Minecraft.getInstance();
    ClientLevel level = client.level;
    if (client.player == null || level == null) {
      return;
    }

    AABB search =
        new AABB(
            x - DUST_TO_HEAD_RADIUS,
            y - DUST_TO_HEAD_RADIUS,
            z - DUST_TO_HEAD_RADIUS,
            x + DUST_TO_HEAD_RADIUS,
            y + DUST_TO_HEAD_RADIUS,
            z + DUST_TO_HEAD_RADIUS);
    boolean found = false;
    for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, search)) {
      if (!isCandidateStand(stand)
          || !isDustNearHead(x, y, z, stand.getX(), stand.getY(), stand.getZ())
          || !hasPairedInteraction(level, stand)) {
        continue;
      }
      found = true;
      UUID id = stand.getUUID();
      MARKED.put(id, stand);
      LAST_DUST_GAME_TIME.put(id, level.getGameTime());
    }
    if (!found) {
      return;
    }
    if (rendered) {
      recordRenderedDust(level.getGameTime(), x, y, z);
    }
  }

  private static void recordRenderedDust(long gameTime, double x, double y, double z) {
    if (pendingDustGameTime != gameTime) {
      pendingDustGameTime = gameTime;
      pendingDustSumX = 0.0;
      pendingDustSumY = 0.0;
      pendingDustSumZ = 0.0;
      pendingDustCount = 0;
    }
    pendingDustCount++;
    pendingDustSumX += x;
    pendingDustSumY += y;
    pendingDustSumZ += z;
    lastRenderedQuestDustGameTime = gameTime;
    beamX = pendingDustSumX / pendingDustCount;
    beamY = pendingDustSumY / pendingDustCount;
    beamZ = pendingDustSumZ / pendingDustCount;
  }

  /** Drops stale targets after their entity leaves the current client level. */
  public static void tick(Minecraft client) {
    if (client.level == null || !isActive()) {
      MARKED.clear();
      LAST_DUST_GAME_TIME.clear();
      clearDustBeam();
      return;
    }
    long gameTime = client.level.getGameTime();
    MARKED
        .entrySet()
        .removeIf(
            entry ->
                isGoneFromLevel(client.level, entry.getValue())
                    || !hasRecentRenderedQuestDust(
                        gameTime,
                        LAST_DUST_GAME_TIME.getOrDefault(entry.getKey(), Long.MIN_VALUE)));
    LAST_DUST_GAME_TIME.keySet().removeIf(id -> !MARKED.containsKey(id));
  }

  /** Drops references when leaving a world; the old level and its entities are being discarded. */
  public static void reset() {
    MARKED.clear();
    LAST_DUST_GAME_TIME.clear();
    clearDustBeam();
  }

  private static void clearDustBeam() {
    lastRenderedQuestDustGameTime = Long.MIN_VALUE;
    pendingDustGameTime = Long.MIN_VALUE;
    pendingDustCount = 0;
  }

  /** Used by the renderer mixin to bypass vanilla entity-distance culling for tracked props. */
  public static boolean isMarked(Entity entity) {
    return entity instanceof ArmorStand stand
        && isActive()
        && !isGoneFromLevel(Minecraft.getInstance().level, stand)
        && MARKED.get(stand.getUUID()) == stand;
  }

  /** Used by the armor-stand renderer, whose render state does not retain the source entity id. */
  public static boolean isMarkedPosition(double x, double y, double z) {
    if (!isActive()) {
      return false;
    }
    for (ArmorStand stand : MARKED.values()) {
      if (isGoneFromLevel(Minecraft.getInstance().level, stand)) {
        continue;
      }
      double dx = x - stand.getX();
      double dy = y - stand.getY();
      double dz = z - stand.getZ();
      if (dx * dx + dy * dy + dz * dz <= MARKED_POSITION_EPSILON_SQUARED) {
        return true;
      }
    }
    return false;
  }

  /**
   * Single through-wall beam origin at the current collectible dust centroid. Null when that dust
   * is not being rendered.
   */
  static Vec3 beamOrigin() {
    Minecraft client = Minecraft.getInstance();
    if (!isActive()
        || client.level == null
        || !hasRecentRenderedQuestDust(client.level.getGameTime(), lastRenderedQuestDustGameTime)) {
      return null;
    }
    return new Vec3(beamX, beamY, beamZ);
  }

  /**
   * Forces a midnight sky only while a distance-bearing quest bar is present and matching quest
   * dust is currently being spawned for rendering. Packet-only observations do not count.
   * Fullbright may still brighten the world, but it must not restore a daytime sky.
   */
  public static boolean shouldForceNight() {
    Minecraft client = Minecraft.getInstance();
    return isActive()
        && client.level != null
        && hasRecentRenderedQuestDust(client.level.getGameTime(), lastRenderedQuestDustGameTime);
  }

  /** Requires a quest boss bar that provides a distance, ignoring local PIM guidance. */
  public static boolean isActive() {
    if (!NotRidingAlertClient.isImagineFunServer()) {
      return false;
    }
    Minecraft client = Minecraft.getInstance();
    return client.player != null
        && client.level != null
        && client.gui != null
        && ((NraBossHealthOverlayAccessor) client.gui.hud.getBossOverlay())
            .getEvents().entrySet().stream()
                .anyMatch(event -> isDistanceBossBar(event.getKey(), event.getValue().getName()));
  }

  static boolean isDistanceBossBar(UUID id, Component title) {
    if (BossBarTracker.PIN_TRADER_BOSS_ID.equals(id) || title == null) {
      return false;
    }
    Matcher matcher = DISTANCE_BOSS_BAR.matcher(title.getString());
    if (!matcher.matches()) {
      return false;
    }
    int distanceStart = matcher.start(1);
    int distanceEnd = matcher.end(1);
    int[] offset = {0};
    // Visit effective styles, so inherited colors and split numeric components work too.
    return title
        .<Boolean>visit(
            (style, text) -> {
              int start = offset[0];
              offset[0] += text.length();
              if (!text.isEmpty()
                  && start < distanceEnd
                  && offset[0] > distanceStart
                  && (style.getColor() == null
                      || style.getColor().getValue() != DISTANCE_TEXT_COLOR)) {
                return Optional.of(false);
              }
              return Optional.empty();
            },
            Style.EMPTY)
        .orElse(true);
  }

  static boolean hasRecentRenderedQuestDust(long gameTime, long lastRenderedGameTime) {
    long age = gameTime - lastRenderedGameTime;
    return age >= 0 && age <= RENDERED_DUST_GRACE_TICKS;
  }

  static Vec3 averageDust(double[] xs, double[] ys, double[] zs) {
    double sumX = 0.0;
    double sumY = 0.0;
    double sumZ = 0.0;
    for (int i = 0; i < xs.length; i++) {
      sumX += xs[i];
      sumY += ys[i];
      sumZ += zs[i];
    }
    return new Vec3(sumX / xs.length, sumY / ys.length, sumZ / zs.length);
  }

  /** DebugBridge health check. */
  public static String describe() {
    Vec3 origin = beamOrigin();
    return "QuestCollectibleGlow{marked="
        + MARKED.size()
        + ", night="
        + shouldForceNight()
        + ", beam="
        + (origin == null ? "none" : origin)
        + "}";
  }

  static boolean isMatchingDust(ParticleOptions options) {
    if (!(options instanceof DustParticleOptions dust)
        || Math.abs(dust.getScale() - 1.0F) > 0.0001F) {
      return false;
    }
    Vector3f color = dust.getColor();
    return channelToByte(color.x()) == 255
        && channelToByte(color.y()) == 255
        && channelToByte(color.z()) == 255;
  }

  static boolean isDustNearHead(
      double dustX, double dustY, double dustZ, double standX, double standY, double standZ) {
    return dustToHeadDistanceSquared(dustX, dustY, dustZ, standX, standY, standZ)
        <= DUST_TO_HEAD_RADIUS_SQUARED;
  }

  static double dustToHeadDistanceSquared(
      double dustX, double dustY, double dustZ, double standX, double standY, double standZ) {
    double dx = dustX - standX;
    double dy = dustY - (standY + HEAD_HEIGHT);
    double dz = dustZ - standZ;
    return dx * dx + dy * dy + dz * dz;
  }

  static boolean isPairedInteractionOrigin(
      double standX,
      double standY,
      double standZ,
      double interactionX,
      double interactionY,
      double interactionZ) {
    double dx = standX - interactionX;
    double dy = standY - interactionY;
    double dz = standZ - interactionZ;
    return dx * dx + dy * dy + dz * dz <= INTERACTION_PAIR_RADIUS_SQUARED;
  }

  private static boolean isCandidateStand(ArmorStand stand) {
    return stand.isInvisible() && !stand.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
  }

  private static boolean hasPairedInteraction(ClientLevel level, ArmorStand stand) {
    AABB pairBox =
        new AABB(
            stand.getX() - INTERACTION_PAIR_RADIUS,
            stand.getY() - INTERACTION_PAIR_RADIUS,
            stand.getZ() - INTERACTION_PAIR_RADIUS,
            stand.getX() + INTERACTION_PAIR_RADIUS,
            stand.getY() + INTERACTION_PAIR_RADIUS,
            stand.getZ() + INTERACTION_PAIR_RADIUS);
    for (Interaction interaction : level.getEntitiesOfClass(Interaction.class, pairBox)) {
      if (isPairedInteractionOrigin(
          stand.getX(),
          stand.getY(),
          stand.getZ(),
          interaction.getX(),
          interaction.getY(),
          interaction.getZ())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isGoneFromLevel(ClientLevel level, ArmorStand stand) {
    return stand.isRemoved() || stand.level() != level;
  }

  private static int channelToByte(float channel) {
    return Math.max(0, Math.min(255, Math.round(channel * 255.0F)));
  }
}
