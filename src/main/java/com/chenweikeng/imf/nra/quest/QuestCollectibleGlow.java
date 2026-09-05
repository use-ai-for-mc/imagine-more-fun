package com.chenweikeng.imf.nra.quest;

import com.chenweikeng.imf.mixin.NraBossHealthOverlayAccessor;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.pim.tracker.BossBarTracker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
 *
 * <p>Talk-to-NPC quests instead float a no-gravity {@code minecraft:iron_pickaxe} named {@code
 * Brickhead} at damage 125 (the exclamation-mark model) 2.75 blocks above an unnamed {@link
 * RemotePlayer}. Those markers get the same through-wall red/blue beam while a distance-bearing
 * quest bar is present.
 */
public final class QuestCollectibleGlow {
  // Observed live: "Quest: Find 5 Honey Pots (1357.5) ⬅". The first number is a count.
  private static final Pattern DISTANCE_BOSS_BAR =
      Pattern.compile("^Quest: .+(\\([0-9]+(?:\\.[0-9]+)?\\))\\s*[⬅➡⬆⬇↖↗↘↙←→↑↓]?\\s*$");
  private static final double EDGE_GLOW_MAX_DISTANCE = 300.0;
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
  // Observed live 2026-09-05: Yoda "Quest: Meditate with Yoda (4.7) ⬆".
  private static final String NPC_MARKER_ITEM_ID = "minecraft:iron_pickaxe";
  private static final String NPC_MARKER_ITEM_NAME = "Brickhead";
  private static final int NPC_MARKER_DAMAGE = 125;
  private static final double NPC_MARKER_HEIGHT = 2.75;
  private static final double NPC_MARKER_HEIGHT_SLACK = 0.5;
  private static final double NPC_MARKER_SCAN_RADIUS = 96.0;

  // Particle observation, ticks, and render-state collection all run on the client thread.
  private static final Map<UUID, ArmorStand> MARKED = new HashMap<>();
  private static final Map<UUID, ItemEntity> MARKED_NPC_MARKERS = new HashMap<>();
  private static final Map<UUID, RemotePlayer> MARKED_NPCS = new HashMap<>();
  private static final Map<UUID, Long> LAST_DUST_GAME_TIME = new HashMap<>();
  private static long lastRenderedQuestDustGameTime = Long.MIN_VALUE;

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
      lastRenderedQuestDustGameTime = level.getGameTime();
    }
  }

  /** Drops stale targets after their entity leaves the current client level. */
  public static void tick(Minecraft client) {
    if (client.level == null || !isActive()) {
      MARKED.clear();
      MARKED_NPC_MARKERS.clear();
      MARKED_NPCS.clear();
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
    refreshNpcMarkers(client);
  }

  /** Drops references when leaving a world; the old level and its entities are being discarded. */
  public static void reset() {
    MARKED.clear();
    MARKED_NPC_MARKERS.clear();
    MARKED_NPCS.clear();
    LAST_DUST_GAME_TIME.clear();
    clearDustBeam();
  }

  private static void clearDustBeam() {
    lastRenderedQuestDustGameTime = Long.MIN_VALUE;
  }

  /** Used by the renderer mixin to bypass vanilla entity-distance culling for tracked props. */
  public static boolean isMarked(Entity entity) {
    if (!isActive()) {
      return false;
    }
    ClientLevel level = Minecraft.getInstance().level;
    if (entity instanceof ArmorStand stand) {
      return !isGoneFromLevel(level, stand) && MARKED.get(stand.getUUID()) == stand;
    }
    if (entity instanceof RemotePlayer npc) {
      return !isGoneFromLevel(level, npc) && MARKED_NPCS.get(npc.getUUID()) == npc;
    }
    return false;
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
   * One stable through-wall beam origin: the nearest tracked quest entity. Collectibles use the
   * armor stand instead of moving dust; talk-to-NPC quests use their marker item.
   */
  static List<Vec3> beamOrigins() {
    Minecraft client = Minecraft.getInstance();
    List<Vec3> origins = new ArrayList<>();
    if (!isActive() || client.level == null) {
      return origins;
    }
    if (hasRecentRenderedQuestDust(client.level.getGameTime(), lastRenderedQuestDustGameTime)) {
      for (ArmorStand stand : MARKED.values()) {
        if (!isGoneFromLevel(client.level, stand)) {
          origins.add(new Vec3(stand.getX(), stand.getY() + 2.0, stand.getZ()));
        }
      }
    }
    for (ItemEntity marker : MARKED_NPC_MARKERS.values()) {
      if (!isGoneFromLevel(client.level, marker)) {
        origins.add(marker.position());
      }
    }
    Vec3 nearest = nearestHorizontalOrigin(origins, client.player.position());
    return nearest == null ? List.of() : List.of(nearest);
  }

  /**
   * Forces a midnight sky only while a distance-bearing quest bar is present and either matching
   * quest dust is being spawned for rendering or an NPC exclamation-mark is loaded. Packet-only
   * dust observations do not count. Fullbright may still brighten the world, but it must not
   * restore a daytime sky.
   */
  public static boolean shouldForceNight() {
    Minecraft client = Minecraft.getInstance();
    return isActive()
        && client.level != null
        && (hasRecentRenderedQuestDust(client.level.getGameTime(), lastRenderedQuestDustGameTime)
            || hasLoadedNpcMarker(client.level));
  }

  /** Requires a quest boss bar that provides a distance, ignoring local PIM guidance. */
  public static boolean isActive() {
    return activeQuestBossBarTitle() != null;
  }

  /** Recomputes the direction to the nearest beam from the rendered camera on every HUD frame. */
  public static double currentGlowAngle() {
    String title = activeQuestBossBarTitle();
    if (!isEdgeGlowWithinRange(title)) {
      return Double.NaN;
    }
    var camera = Minecraft.getInstance().gameRenderer.mainCamera();
    if (!camera.isInitialized()) {
      return Double.NaN;
    }
    return QuestGlowDirection.angleToNearestBeam(beamOrigins(), camera.position(), camera.yRot());
  }

  static boolean isEdgeGlowWithinRange(String title) {
    if (title == null) {
      return false;
    }
    Matcher matcher = DISTANCE_BOSS_BAR.matcher(title);
    if (!matcher.matches()) {
      return false;
    }
    String distanceText = matcher.group(1);
    double distance = Double.parseDouble(distanceText.substring(1, distanceText.length() - 1));
    return distance <= EDGE_GLOW_MAX_DISTANCE;
  }

  private static String activeQuestBossBarTitle() {
    if (!NotRidingAlertClient.isImagineFunServer()) {
      return null;
    }
    Minecraft client = Minecraft.getInstance();
    if (client.player == null || client.level == null || client.gui == null) {
      return null;
    }
    for (var event :
        ((NraBossHealthOverlayAccessor) client.gui.hud.getBossOverlay()).getEvents().entrySet()) {
      if (isDistanceBossBar(event.getKey(), event.getValue().getName())) {
        return event.getValue().getName().getString();
      }
    }
    return null;
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

  static Vec3 nearestHorizontalOrigin(List<Vec3> origins, Vec3 observer) {
    Vec3 nearest = null;
    double nearestDistance = Double.POSITIVE_INFINITY;
    for (Vec3 origin : origins) {
      double dx = origin.x - observer.x;
      double dz = origin.z - observer.z;
      double distance = dx * dx + dz * dz;
      if (distance < nearestDistance
          || (distance == nearestDistance
              && nearest != null
              && (origin.x < nearest.x || (origin.x == nearest.x && origin.z < nearest.z)))) {
        nearest = origin;
        nearestDistance = distance;
      }
    }
    return nearest;
  }

  /** DebugBridge health check. */
  public static String describe() {
    List<Vec3> origins = beamOrigins();
    return "QuestCollectibleGlow{marked="
        + MARKED.size()
        + ", npcMarkers="
        + MARKED_NPC_MARKERS.size()
        + ", npcs="
        + MARKED_NPCS.size()
        + ", night="
        + shouldForceNight()
        + ", beam="
        + (origins.isEmpty() ? "none" : origins)
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

  static boolean isNpcMarkerItem(String itemId, String itemName, int damage) {
    return NPC_MARKER_ITEM_ID.equals(itemId)
        && NPC_MARKER_ITEM_NAME.equals(itemName)
        && damage == NPC_MARKER_DAMAGE;
  }

  static boolean isPairedNpcMarker(
      double markerX, double markerY, double markerZ, double npcX, double npcY, double npcZ) {
    double dx = markerX - npcX;
    double dz = markerZ - npcZ;
    if (dx * dx + dz * dz > INTERACTION_PAIR_RADIUS_SQUARED) {
      return false;
    }
    double dy = markerY - npcY;
    return dy >= NPC_MARKER_HEIGHT - NPC_MARKER_HEIGHT_SLACK
        && dy <= NPC_MARKER_HEIGHT + NPC_MARKER_HEIGHT_SLACK;
  }

  private static void refreshNpcMarkers(Minecraft client) {
    MARKED_NPC_MARKERS.clear();
    MARKED_NPCS.clear();
    ClientLevel level = client.level;
    if (client.player == null || level == null) {
      return;
    }
    AABB scan = client.player.getBoundingBox().inflate(NPC_MARKER_SCAN_RADIUS);
    for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, scan)) {
      if (!item.isNoGravity() || !isNpcMarkerStack(item.getItem())) {
        continue;
      }
      RemotePlayer npc = findPairedNpc(level, item);
      if (npc == null) {
        continue;
      }
      MARKED_NPC_MARKERS.put(item.getUUID(), item);
      MARKED_NPCS.put(npc.getUUID(), npc);
    }
  }

  private static boolean isNpcMarkerStack(ItemStack stack) {
    if (stack.isEmpty()) {
      return false;
    }
    Component itemName = stack.get(DataComponents.ITEM_NAME);
    if (itemName == null) {
      return false;
    }
    return isNpcMarkerItem(
        BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
        itemName.getString(),
        stack.getDamageValue());
  }

  private static RemotePlayer findPairedNpc(ClientLevel level, ItemEntity item) {
    AABB pairBox =
        new AABB(
            item.getX() - INTERACTION_PAIR_RADIUS,
            item.getY() - (NPC_MARKER_HEIGHT + NPC_MARKER_HEIGHT_SLACK),
            item.getZ() - INTERACTION_PAIR_RADIUS,
            item.getX() + INTERACTION_PAIR_RADIUS,
            item.getY() - (NPC_MARKER_HEIGHT - NPC_MARKER_HEIGHT_SLACK),
            item.getZ() + INTERACTION_PAIR_RADIUS);
    for (Player player : level.getEntitiesOfClass(Player.class, pairBox)) {
      if (player instanceof RemotePlayer npc
          && isPairedNpcMarker(
              item.getX(), item.getY(), item.getZ(), npc.getX(), npc.getY(), npc.getZ())) {
        return npc;
      }
    }
    return null;
  }

  private static boolean hasLoadedNpcMarker(ClientLevel level) {
    for (ItemEntity marker : MARKED_NPC_MARKERS.values()) {
      if (!isGoneFromLevel(level, marker)) {
        return true;
      }
    }
    return false;
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

  private static boolean isGoneFromLevel(ClientLevel level, Entity entity) {
    return entity.isRemoved() || entity.level() != level;
  }

  private static int channelToByte(float channel) {
    return Math.max(0, Math.min(255, Math.round(channel * 255.0F)));
  }
}
