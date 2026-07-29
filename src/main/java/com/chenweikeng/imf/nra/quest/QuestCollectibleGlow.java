package com.chenweikeng.imf.nra.quest;

import com.chenweikeng.imf.mixin.NraBossHealthOverlayAccessor;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
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
  private static final double DUST_TO_HEAD_RADIUS = 2.25;
  private static final double DUST_TO_HEAD_RADIUS_SQUARED =
      DUST_TO_HEAD_RADIUS * DUST_TO_HEAD_RADIUS;
  private static final double HEAD_HEIGHT = 1.5;
  private static final double INTERACTION_PAIR_RADIUS = 0.35;
  private static final double MARKED_POSITION_EPSILON_SQUARED = 0.01;

  private static final Map<UUID, ArmorStand> MARKED = new ConcurrentHashMap<>();

  private QuestCollectibleGlow() {}

  /** Observes a particle before its configured color is randomized for display. */
  public static void observeDust(ParticleOptions options, double x, double y, double z) {
    if (!isMatchingDust(options) || !NotRidingAlertClient.isImagineFunServer()) {
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
    for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, search)) {
      if (!isCandidateStand(stand)
          || !isDustNearHead(x, y, z, stand.getX(), stand.getY(), stand.getZ())
          || !hasPairedInteraction(level, stand)) {
        continue;
      }
      MARKED.put(stand.getUUID(), stand);
    }
  }

  /** Drops stale targets after their entity leaves the current client level. */
  public static void tick(Minecraft client) {
    if (client.level == null) {
      MARKED.clear();
      return;
    }
    MARKED.entrySet().removeIf(entry -> isGoneFromLevel(client.level, entry.getValue()));
  }

  /** Drops references when leaving a world; the old level and its entities are being discarded. */
  public static void reset() {
    MARKED.clear();
  }

  /** Used by the renderer mixin to bypass vanilla entity-distance culling for tracked props. */
  public static boolean isMarked(Entity entity) {
    return entity instanceof ArmorStand stand && MARKED.get(stand.getUUID()) == stand;
  }

  /** Used by the armor-stand renderer, whose render state does not retain the source entity id. */
  public static boolean isMarkedPosition(double x, double y, double z) {
    for (ArmorStand stand : MARKED.values()) {
      double dx = x - stand.getX();
      double dy = y - stand.getY();
      double dz = z - stand.getZ();
      if (dx * dx + dy * dy + dz * dz <= MARKED_POSITION_EPSILON_SQUARED) {
        return true;
      }
    }
    return false;
  }

  /** Stable per-frame snapshot used by the independent waypoint-beam renderer. */
  static List<ArmorStand> markedStands() {
    return List.copyOf(MARKED.values());
  }

  /** True while an active quest boss bar and an identified quest prop are both present. */
  public static boolean shouldForceNight() {
    if (MARKED.isEmpty() || !NotRidingAlertClient.isImagineFunServer()) {
      return false;
    }
    Minecraft client = Minecraft.getInstance();
    return client.gui != null
        && !((NraBossHealthOverlayAccessor) client.gui.getBossOverlay()).getEvents().isEmpty();
  }

  /** DebugBridge health check. */
  public static String describe() {
    return "QuestCollectibleGlow{marked=" + MARKED.size() + "}";
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
    double dx = dustX - standX;
    double dy = dustY - (standY + HEAD_HEIGHT);
    double dz = dustZ - standZ;
    return dx * dx + dy * dy + dz * dz <= DUST_TO_HEAD_RADIUS_SQUARED;
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
    return !level.getEntitiesOfClass(Interaction.class, pairBox).isEmpty();
  }

  private static boolean isGoneFromLevel(ClientLevel level, ArmorStand stand) {
    return stand.isRemoved() || stand.level() != level;
  }

  private static int channelToByte(float channel) {
    return Math.max(0, Math.min(255, Math.round(channel * 255.0F)));
  }
}
