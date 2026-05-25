package com.chenweikeng.imf.nra.redcartrolley;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Shared detection for the Red Car Trolley trains. ImagineFun builds each train from a rigid
 * cluster of armor stands; its cars wear a custom-modelled iron shovel in the helmet slot. Damage
 * 29 marks the body cars (two per train) and damage 31 marks the overhead electric-wire/pole
 * element — a cleaner single-point indicator of where the train is. We match both and group nearby
 * cars into trains, ignoring an unrelated damage-165 iron_shovel mover seen elsewhere on the map.
 *
 * <p>The recorder logs each car's damage, so prediction can later prefer the damage-31 point. Used
 * by the capture recorder now and by route prediction later.
 */
public final class RctTrains {
  /** Helmet item that marks a train car. */
  public static final String CAR_ITEM_ID = "minecraft:iron_shovel";

  /**
   * Custom-model damage values of trolley cars: 29 = body cars (two per train), 31 = the
   * electric-wire/pole element (one per train, a cleaner single-point indicator).
   */
  public static final Set<Integer> CAR_DAMAGES = Set.of(29, 31);

  /** How far out to look for cars (blocks). Bounded in practice by server entity-tracking range. */
  private static final double DETECT_RADIUS = 96.0;

  /** Cars within this horizontal distance of an existing group join it (blocks). */
  private static final double CLUSTER_RADIUS = 12.0;

  private RctTrains() {}

  /** One train car: armor-stand position, helmet-shovel damage, and the armor stand's entity id. */
  public record Car(double x, double y, double z, int damage, int id) {}

  /** A detected train: the centroid of its cars plus the individual cars. */
  public record Cluster(double cx, double cy, double cz, List<Car> cars) {}

  /** True if {@code stand} is a trolley car (iron shovel, damage in {@link #CAR_DAMAGES}). */
  public static boolean isCar(ArmorStand stand) {
    ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
    if (head.isEmpty() || !CAR_DAMAGES.contains(head.getDamageValue())) {
      return false;
    }
    return BuiltInRegistries.ITEM.getKey(head.getItem()).toString().equals(CAR_ITEM_ID);
  }

  /** All trains currently loaded near the player, each grouped from its cars. Never null. */
  public static List<Cluster> detect(Minecraft client) {
    if (client.player == null || client.level == null) {
      return List.of();
    }
    AABB box = client.player.getBoundingBox().inflate(DETECT_RADIUS);
    List<ArmorStand> cars =
        client.level.getEntitiesOfClass(ArmorStand.class, box, RctTrains::isCar);
    return cluster(cars);
  }

  /** Greedy single-link grouping by horizontal distance; trains are short and far apart. */
  private static List<Cluster> cluster(List<ArmorStand> cars) {
    List<List<ArmorStand>> groups = new ArrayList<>();
    for (ArmorStand car : cars) {
      List<ArmorStand> match = null;
      for (List<ArmorStand> group : groups) {
        for (ArmorStand member : group) {
          if (horizDistSq(car.getX(), car.getZ(), member.getX(), member.getZ())
              <= CLUSTER_RADIUS * CLUSTER_RADIUS) {
            match = group;
            break;
          }
        }
        if (match != null) {
          break;
        }
      }
      if (match == null) {
        match = new ArrayList<>();
        groups.add(match);
      }
      match.add(car);
    }

    List<Cluster> clusters = new ArrayList<>();
    for (List<ArmorStand> group : groups) {
      double sx = 0;
      double sy = 0;
      double sz = 0;
      List<Car> members = new ArrayList<>();
      for (ArmorStand car : group) {
        sx += car.getX();
        sy += car.getY();
        sz += car.getZ();
        int damage = car.getItemBySlot(EquipmentSlot.HEAD).getDamageValue();
        members.add(new Car(car.getX(), car.getY(), car.getZ(), damage, car.getId()));
      }
      int n = group.size();
      clusters.add(new Cluster(sx / n, sy / n, sz / n, members));
    }
    return clusters;
  }

  private static double horizDistSq(double ax, double az, double bx, double bz) {
    double dx = ax - bx;
    double dz = az - bz;
    return dx * dx + dz * dz;
  }
}
