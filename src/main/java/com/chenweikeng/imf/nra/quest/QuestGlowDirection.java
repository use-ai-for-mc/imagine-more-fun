package com.chenweikeng.imf.nra.quest;

import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * HUD layout anchors plus continuous camera-relative bearings computed locally from beam positions.
 * The symbol order matches {@code BossBarTracker.getDirection}: ⬆↗➡↘⬇↙⬅↖ clockwise from the
 * player's forward.
 */
public enum QuestGlowDirection {
  NONE,
  UP,
  UP_RIGHT,
  RIGHT,
  DOWN_RIGHT,
  DOWN,
  DOWN_LEFT,
  LEFT,
  UP_LEFT;

  /** Same sequence PIM writes onto its local pin-trader bar. */
  static final String[] SYMBOLS = {"⬆", "↗", "➡", "↘", "⬇", "↙", "⬅", "↖"};

  static QuestGlowDirection fromSymbol(String symbol) {
    if (symbol == null || symbol.isEmpty()) {
      return NONE;
    }
    return switch (symbol) {
      case "⬆", "↑" -> UP;
      case "↗" -> UP_RIGHT;
      case "➡", "→" -> RIGHT;
      case "↘" -> DOWN_RIGHT;
      case "⬇", "↓" -> DOWN;
      case "↙" -> DOWN_LEFT;
      case "⬅", "←" -> LEFT;
      case "↖" -> UP_LEFT;
      default -> NONE;
    };
  }

  /** Trailing arrow only. Quest names that happen to contain an arrow elsewhere do not match. */
  public static QuestGlowDirection fromBossBarTitle(String title) {
    if (title == null) {
      return NONE;
    }
    String trimmed = title.strip();
    if (trimmed.isEmpty()) {
      return NONE;
    }
    return fromSymbol(trimmed.substring(trimmed.length() - 1));
  }

  /** The beam is vertical, so elevation does not affect either selection or direction. */
  static double angleToNearestBeam(List<Vec3> origins, Vec3 camera, float yaw) {
    Vec3 nearest = null;
    double nearestDistance = Double.POSITIVE_INFINITY;
    for (Vec3 origin : origins) {
      double dx = origin.x - camera.x;
      double dz = origin.z - camera.z;
      double distance = dx * dx + dz * dz;
      if (distance < nearestDistance
          || (distance == nearestDistance
              && nearest != null
              && (origin.x < nearest.x || (origin.x == nearest.x && origin.z < nearest.z)))) {
        nearest = origin;
        nearestDistance = distance;
      }
    }
    if (nearest == null) {
      return Double.NaN;
    }
    if (nearestDistance < 1.0e-8) {
      return 0.0;
    }
    double bearing = Math.toDegrees(Math.atan2(-(nearest.x - camera.x), nearest.z - camera.z));
    return normalizeAngle(bearing - yaw);
  }

  static double normalizeAngle(double angle) {
    return ((angle % 360.0) + 360.0) % 360.0;
  }

  boolean showsEdgeGlow() {
    return this != NONE;
  }
}
