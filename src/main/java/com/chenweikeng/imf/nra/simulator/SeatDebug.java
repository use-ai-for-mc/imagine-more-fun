package com.chenweikeng.imf.nra.simulator;

import com.chenweikeng.imf.ImfClient;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;

/**
 * Temporary debug tool: snapshots the user's mounted armor stand and all nearby invisible empty
 * armor stands on three transitions —
 *
 * <ul>
 *   <li>Mount (vehicle goes from null → ArmorStand)
 *   <li>Vehicle change (one ArmorStand → another)
 *   <li>Dismount (ArmorStand → null), plus a follow-up check ~1s later to see whether the
 *       previously-mounted stand still exists.
 * </ul>
 *
 * Each snapshot lists every nearby invisible armor stand with its id, position relative to the
 * user, equipment, and passenger count. Output goes to both chat (truncated) and the mod log
 * (full). Toggle via {@code /imf-sim seatdebug}.
 *
 * <p>This is throwaway diagnostic code — delete after the seat-anatomy hypothesis is confirmed.
 */
public final class SeatDebug {

  private static final SeatDebug INSTANCE = new SeatDebug();
  private static final double SCAN_RADIUS = 5.0;

  /** Re-check the previously-mounted stand 20 ticks (1s) after dismount. */
  private static final int POST_DISMOUNT_DELAY_TICKS = 20;

  private final EquipmentSlot[] slots = {
    EquipmentSlot.MAINHAND,
    EquipmentSlot.OFFHAND,
    EquipmentSlot.HEAD,
    EquipmentSlot.CHEST,
    EquipmentSlot.LEGS,
    EquipmentSlot.FEET,
  };

  private boolean enabled;
  private Integer prevVehicleId;

  /** id of the most recently dismounted stand and the tick we should follow up at. */
  private int dismountedStandId = -1;

  private long followUpAtTick = -1;
  private long tickCount;

  private SeatDebug() {}

  public static SeatDebug get() {
    return INSTANCE;
  }

  public static void init() {
    ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::onTick);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
    ImfClient.LOGGER.info("SeatDebug: enabled={}", enabled);
  }

  private void onTick(Minecraft mc) {
    tickCount++;
    if (!enabled) return;
    if (mc.player == null || mc.level == null) return;

    Entity vehicle = mc.player.getVehicle();
    Integer vId = (vehicle instanceof ArmorStand) ? vehicle.getId() : null;

    boolean mounted = vId != null && prevVehicleId == null;
    boolean dismounted = vId == null && prevVehicleId != null;
    boolean changed = vId != null && prevVehicleId != null && !prevVehicleId.equals(vId);

    if (mounted) {
      snapshot(mc, "MOUNT", vehicle);
    } else if (changed) {
      snapshot(mc, "VEHICLE-CHANGE (was id=" + prevVehicleId + ")", vehicle);
    } else if (dismounted) {
      dismountedStandId = prevVehicleId;
      followUpAtTick = tickCount + POST_DISMOUNT_DELAY_TICKS;
      snapshot(mc, "DISMOUNT (prev id=" + prevVehicleId + ")", null);
    }

    // Post-dismount follow-up: did the previously-mounted stand persist? Did its y change?
    if (followUpAtTick > 0 && tickCount >= followUpAtTick) {
      Entity prevStand = mc.level.getEntity(dismountedStandId);
      if (prevStand == null || prevStand.isRemoved()) {
        report(
            String.format(
                "[%dt later] previously-mounted stand id=%d is GONE/removed",
                POST_DISMOUNT_DELAY_TICKS, dismountedStandId));
      } else {
        LocalPlayer p = mc.player;
        report(
            String.format(
                "[%dt later] previously-mounted stand id=%d STILL EXISTS at (%.4f, %.4f, %.4f) — Δfromuser=(%+.2f, %+.2f, %+.2f) — passengers=%d",
                POST_DISMOUNT_DELAY_TICKS,
                dismountedStandId,
                prevStand.getX(),
                prevStand.getY(),
                prevStand.getZ(),
                prevStand.getX() - p.getX(),
                prevStand.getY() - p.getY(),
                prevStand.getZ() - p.getZ(),
                prevStand.getPassengers().size()));
      }
      dismountedStandId = -1;
      followUpAtTick = -1;
    }

    prevVehicleId = vId;
  }

  private void snapshot(Minecraft mc, String label, Entity vehicle) {
    LocalPlayer p = mc.player;
    StringBuilder sb = new StringBuilder();
    sb.append("=== SeatDebug ").append(label).append(" ===\n");
    sb.append(
        String.format(
            "user pos=(%.4f, %.4f, %.4f) yaw=%.1f%n", p.getX(), p.getY(), p.getZ(), p.getYRot()));
    if (vehicle != null) {
      sb.append(
          String.format(
              "vehicle id=%d pos=(%.4f, %.4f, %.4f) yaw=%.1f isInvisible=%s isSilent=%s%n",
              vehicle.getId(),
              vehicle.getX(),
              vehicle.getY(),
              vehicle.getZ(),
              vehicle.getYRot(),
              ((ArmorStand) vehicle).isInvisible(),
              vehicle.isSilent()));
    }

    // Reference y for "Δy" — vehicle's y if mounted, else user's y minus 1.4 (approx seat y)
    double refY = (vehicle != null) ? vehicle.getY() : (p.getY() - 1.4);
    double refX = p.getX();
    double refZ = p.getZ();

    AABB box = new AABB(p.position(), p.position()).inflate(SCAN_RADIUS, 3.0, SCAN_RADIUS);
    List<ArmorStand> nearby = new ArrayList<>();
    for (Entity e : mc.level.entitiesForRendering()) {
      if (!(e instanceof ArmorStand stand)) continue;
      if (!box.contains(stand.position())) continue;
      nearby.add(stand);
    }
    sb.append("nearby armor stands (").append(nearby.size()).append("):\n");
    for (ArmorStand stand : nearby) {
      String equip = describeEquipment(stand);
      sb.append(
          String.format(
              "  id=%d Δ=(%+.2f, %+.2f, %+.2f) y=%.4f invis=%s eq=%s pass=%d%n",
              stand.getId(),
              stand.getX() - refX,
              stand.getY() - refY,
              stand.getZ() - refZ,
              stand.getY(),
              stand.isInvisible(),
              equip,
              stand.getPassengers().size()));
    }
    String full = sb.toString();
    ImfClient.LOGGER.info("\n{}", full);
    // Chat — emit a short header + ask the user to look at the log for the table
    p.displayClientMessage(
        Component.literal(
            "[SeatDebug] "
                + label
                + " — vehicle="
                + (vehicle == null ? "null" : "id=" + vehicle.getId())
                + " — "
                + nearby.size()
                + " nearby stands; full table in log"),
        false);
  }

  private void report(String msg) {
    ImfClient.LOGGER.info("SeatDebug: {}", msg);
    Minecraft mc = Minecraft.getInstance();
    if (mc.player != null) {
      mc.player.displayClientMessage(Component.literal("[SeatDebug] " + msg), false);
    }
  }

  private String describeEquipment(ArmorStand stand) {
    StringBuilder eq = new StringBuilder();
    boolean any = false;
    for (EquipmentSlot s : slots) {
      var stack = stand.getItemBySlot(s);
      if (!stack.isEmpty()) {
        if (any) eq.append(',');
        eq.append(s.getName()).append('=').append(stack.getItem());
        any = true;
      }
    }
    return any ? eq.toString() : "(empty)";
  }
}
