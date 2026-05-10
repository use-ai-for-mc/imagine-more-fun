package com.chenweikeng.imf.nra.simulator;

import com.chenweikeng.imf.ImfClient;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Owns the in-world ghost entities and the ride state machine.
 *
 * <p>Step 3: opt-in armed mode. While armed, mounting an ImagineFun ride armor stand triggers a
 * scan for sibling empty invisible armor stands; we mount roster ghosts on them via {@link
 * Entity#startRiding(Entity, boolean)} so vanilla handles seat positioning, lean during turns, and
 * the sit pose for free.
 *
 * <p>If the server overrides our client-side mount (sends {@code ClientboundSetPassengersPacket}
 * for that stand), the ghost gets dismounted; we detect that and despawn rather than fight.
 */
public final class GhostManager {

  private static final GhostManager INSTANCE = new GhostManager();

  /** Synthetic entity-id space well above any server-allocated id. */
  private static final int SYNTH_ID_BASE = Integer.MAX_VALUE - 10_000;

  /** Horizontal search radius around the user's vehicle when looking for sibling empty seats. */
  private static final double SEAT_SEARCH_RADIUS = 5.0;

  /**
   * Maximum vertical delta between a candidate seat and the user's seat.
   *
   * <p>Originally tightened to 0.1 after observing ghost-on-roof artifacts. But on multi-bench
   * vehicles like the Main Street Vehicles double-decker omnibus, the bench seats sit at exactly
   * +0.32y above the floor stand the user mounts on — and they are functionally identical stands
   * (same invisible, same dimensions, same empty-equipment, same yaw, no custom-name). So 0.4
   * catches the bench row while still rejecting the other-deck row (+2.3y) and the high-roof
   * decorations.
   *
   * <p>The cost: ghosts on bench rows render ~0.32 blocks visually higher than the user. That's
   * acceptable given the alternative is no ghosts at all on these carts.
   */
  private static final double SEAT_Y_TOLERANCE = 0.4;

  /**
   * Minimum horizontal distance from the user's seat (and from any already-claimed seat). Distinct
   * physical seats are at least ~0.7 blocks apart on every ride we've inspected; anything closer is
   * almost certainly a colocated decoration stand sharing the seat position.
   */
  private static final double MIN_SEAT_SEPARATION = 0.7;

  private static final EquipmentSlot[] ALL_SLOTS = {
    EquipmentSlot.MAINHAND,
    EquipmentSlot.OFFHAND,
    EquipmentSlot.HEAD,
    EquipmentSlot.CHEST,
    EquipmentSlot.LEGS,
    EquipmentSlot.FEET,
  };

  private final Map<UUID, GhostRider> active = new LinkedHashMap<>();
  private int nextSynthId = SYNTH_ID_BASE;

  private boolean armed;

  /** Entity id of the player's vehicle on the previous tick, or null if not mounted. */
  private Integer lastVehicleId;

  private GhostManager() {}

  public static GhostManager get() {
    return INSTANCE;
  }

  public static void init() {
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> INSTANCE.reset());
    ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::onClientTick);
  }

  // ─── armed-mode API ────────────────────────────────────────────────────────────────────────────

  public boolean isArmed() {
    return armed;
  }

  public void setArmed(boolean armed) {
    if (this.armed == armed) return;
    this.armed = armed;
    ImfClient.LOGGER.info("GhostManager: armed={}", armed);
    if (!armed) {
      despawnAll();
      lastVehicleId = null;
    }
  }

  // ─── tick loop ─────────────────────────────────────────────────────────────────────────────────

  private void onClientTick(Minecraft mc) {
    if (!armed) return;
    if (mc.player == null || mc.level == null) return;

    // ImagineFun parks players on armor stands at queue / dismount / standing positions, not just
    // during actual rides. Use NRA's scoreboard-derived ride state as the authoritative gate so we
    // only mount ghosts during a real ride. The vehicle-instanceof-ArmorStand check is still needed
    // because mounting requires an ArmorStand seat.
    Entity currentVehicle = mc.player.getVehicle();
    boolean onRealRide =
        CurrentRideHolder.getCurrentRide() != null && currentVehicle instanceof ArmorStand;
    Integer currentVehicleId = onRealRide ? currentVehicle.getId() : null;

    boolean wasRiding = lastVehicleId != null;
    boolean isRiding = currentVehicleId != null;
    boolean vehicleChanged = wasRiding && isRiding && !lastVehicleId.equals(currentVehicleId);

    if (wasRiding && (!isRiding || vehicleChanged)) {
      onRideEnd();
    }
    if (isRiding && (!wasRiding || vehicleChanged)) {
      onRideStart(currentVehicle);
    }
    if (isRiding) {
      checkGhostMounts();
    }

    lastVehicleId = currentVehicleId;
  }

  private void onRideStart(Entity userVehicle) {
    List<ArmorStand> emptySeats = findEmptySeats(userVehicle);
    ImfClient.LOGGER.info(
        "GhostManager: ride start — vehicle id={} ({} empty seats nearby)",
        userVehicle.getId(),
        emptySeats.size());
    int seatIdx = 0;
    for (GhostSpec spec : GhostRoster.all()) {
      if (seatIdx >= emptySeats.size()) {
        ImfClient.LOGGER.info("GhostManager: no more empty seats; {} ghost(s) seated", seatIdx);
        break;
      }
      mountGhostOnSeat(spec, emptySeats.get(seatIdx++));
    }
  }

  private void onRideEnd() {
    if (active.isEmpty()) return;
    ImfClient.LOGGER.info("GhostManager: ride end — despawning {} ghost(s)", active.size());
    despawnAll();
  }

  /**
   * Detect ghosts that the server has dismounted (e.g. by overriding the seat's passenger list).
   */
  private void checkGhostMounts() {
    List<UUID> toRemove = null;
    for (var entry : active.entrySet()) {
      GhostRider ghost = entry.getValue();
      Entity vehicle = ghost.getVehicle();
      if (vehicle == null || vehicle.isRemoved()) {
        if (toRemove == null) toRemove = new ArrayList<>();
        toRemove.add(entry.getKey());
      }
    }
    if (toRemove == null) return;
    for (UUID uuid : toRemove) {
      GhostRider ghost = active.get(uuid);
      ImfClient.LOGGER.info(
          "GhostManager: {} was dismounted (server override or seat removed); despawning",
          ghost == null ? uuid : ghost.spec().name());
      despawn(uuid);
    }
  }

  // ─── seat detection ────────────────────────────────────────────────────────────────────────────

  /**
   * Return the empty real seats around the user's vehicle, in arbitrary order.
   *
   * <p>Strict by design: only stands that share the user's exact seat y, carry no equipment, and
   * sit at least {@link #MIN_SEAT_SEPARATION} away from any already-claimed seat. On rides where
   * ImagineFun doesn't expose a stand for an unoccupied seat, this returns an empty list and the
   * roster simply gets fewer ghosts than entries — never silently mounted on a decoration.
   *
   * <p>Future extension: ride-specific overrides could supply synthetic seat positions (e.g. by
   * recognizing the cart's support structure and synthesizing armor stands ourselves). When that
   * lands, layer it on top of this method — keep this strict filter as the trusted baseline.
   */
  private List<ArmorStand> findEmptySeats(Entity userVehicle) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.level == null) return List.of();
    double userSeatY = userVehicle.getY();
    double userSeatX = userVehicle.getX();
    double userSeatZ = userVehicle.getZ();
    AABB box =
        userVehicle
            .getBoundingBox()
            .inflate(SEAT_SEARCH_RADIUS, SEAT_Y_TOLERANCE, SEAT_SEARCH_RADIUS);
    List<ArmorStand> result = new ArrayList<>();
    for (Entity e : mc.level.entitiesForRendering()) {
      if (!(e instanceof ArmorStand stand)) continue;
      if (stand == userVehicle) continue;
      if (!stand.isInvisible()) continue;
      if (!stand.getPassengers().isEmpty()) continue;
      if (!box.contains(stand.position())) continue;
      // Y tolerance: real seats share the y-row of the user's seat. Decoration stands (awnings,
      // masts, cart props) sit several blocks above or below.
      if (Math.abs(stand.getY() - userSeatY) > SEAT_Y_TOLERANCE) continue;
      // Decoration stands carry the visible cart parts as equipment items. Real seat stands are
      // always empty (nothing in head / hands / armor).
      if (hasAnyEquipment(stand)) continue;
      // Reject stands colocated with the user's seat — those are decoration stands sharing the
      // seat position (they'd render the ghost on top of the user).
      if (horizontalDist(stand, userSeatX, userSeatZ) < MIN_SEAT_SEPARATION) continue;
      // Reject stands too close to any seat we've already claimed — same reasoning.
      boolean tooCloseToClaimed = false;
      for (ArmorStand claimed : result) {
        if (horizontalDist(stand, claimed.getX(), claimed.getZ()) < MIN_SEAT_SEPARATION) {
          tooCloseToClaimed = true;
          break;
        }
      }
      if (tooCloseToClaimed) continue;
      result.add(stand);
    }
    return result;
  }

  private static double horizontalDist(Entity e, double x, double z) {
    double dx = e.getX() - x;
    double dz = e.getZ() - z;
    return Math.sqrt(dx * dx + dz * dz);
  }

  private static boolean hasAnyEquipment(ArmorStand stand) {
    for (EquipmentSlot slot : ALL_SLOTS) {
      if (!stand.getItemBySlot(slot).isEmpty()) return true;
    }
    return false;
  }

  // ─── mounting ──────────────────────────────────────────────────────────────────────────────────

  private void mountGhostOnSeat(GhostSpec spec, ArmorStand seat) {
    if (active.containsKey(spec.uuid())) return;

    if (!ProfileInjector.isInjected(spec.uuid())) {
      ProfileInjector.inject(spec);
    }

    Minecraft mc = Minecraft.getInstance();
    ClientLevel level = mc.level;
    if (level == null) return;

    GhostRider ghost = new GhostRider(level, spec);
    ghost.setId(nextSynthId++);
    ghost.absSnapTo(seat.getX(), seat.getY(), seat.getZ(), seat.getYRot(), 0f);
    ghost.setYHeadRot(seat.getYRot());
    ghost.setYBodyRot(seat.getYRot());
    ghost.setOldPosAndRot();
    ghost.yBodyRotO = ghost.yBodyRot;
    ghost.yHeadRotO = ghost.yHeadRot;

    level.addEntity(ghost);
    ghost.applyEquipment();
    // 1.21.11: startRiding(Entity, boolean force, boolean sendEventAndTriggers).
    // sendEventAndTriggers=false because we don't want server-side advancement triggers fired
    // by a client-only mount.
    boolean mounted = ghost.startRiding(seat, /* force */ true, /* sendEventAndTriggers */ false);
    active.put(spec.uuid(), ghost);

    ImfClient.LOGGER.info(
        "GhostManager: mounted {} on stand id={} (startRiding={})",
        spec.name(),
        seat.getId(),
        mounted);
  }

  // ─── single-spawn API kept for /imf-sim spawn debug command ────────────────────────────────────

  public String spawnInFrontOfPlayer(GhostSpec spec) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null) return "no player or level";
    if (active.containsKey(spec.uuid())) return spec.name() + " is already spawned";

    if (!ProfileInjector.isInjected(spec.uuid())) {
      ProfileInjector.inject(spec);
    }

    ClientLevel level = mc.level;
    GhostRider ghost = new GhostRider(level, spec);
    ghost.setId(nextSynthId++);

    Vec3 forward = Vec3.directionFromRotation(0f, mc.player.getYRot());
    Vec3 spawnPos = mc.player.position().add(forward.scale(2.0));
    float ghostYaw = mc.player.getYRot() + 180f;
    ghost.absSnapTo(spawnPos.x, spawnPos.y, spawnPos.z, ghostYaw, 0f);
    ghost.setYHeadRot(ghostYaw);
    ghost.setYBodyRot(ghostYaw);
    ghost.setOldPosAndRot();
    ghost.yBodyRotO = ghost.yBodyRot;
    ghost.yHeadRotO = ghost.yHeadRot;

    level.addEntity(ghost);
    ghost.applyEquipment();
    active.put(spec.uuid(), ghost);
    ImfClient.LOGGER.info("GhostManager: spawned {} (id={})", spec.name(), ghost.getId());
    return null;
  }

  // ─── despawn ───────────────────────────────────────────────────────────────────────────────────

  public boolean despawn(UUID uuid) {
    GhostRider ghost = active.remove(uuid);
    if (ghost == null) return false;
    Minecraft mc = Minecraft.getInstance();
    if (ghost.isPassenger()) {
      ghost.stopRiding();
    }
    if (mc.level != null) {
      mc.level.removeEntity(ghost.getId(), Entity.RemovalReason.DISCARDED);
    }
    ImfClient.LOGGER.info("GhostManager: despawned {} (id={})", ghost.spec().name(), ghost.getId());
    return true;
  }

  public int despawnAll() {
    int n = 0;
    Minecraft mc = Minecraft.getInstance();
    ClientLevel level = mc.level;
    Map<UUID, GhostRider> snapshot = new HashMap<>(active);
    active.clear();
    for (GhostRider ghost : snapshot.values()) {
      if (ghost.isPassenger()) {
        ghost.stopRiding();
      }
      if (level != null) {
        level.removeEntity(ghost.getId(), Entity.RemovalReason.DISCARDED);
      }
      n++;
    }
    if (n > 0) {
      ImfClient.LOGGER.info("GhostManager: despawned {} ghost(s)", n);
    }
    return n;
  }

  private void reset() {
    despawnAll();
    armed = false;
    lastVehicleId = null;
  }

  public boolean isSpawned(UUID uuid) {
    return active.containsKey(uuid);
  }
}
