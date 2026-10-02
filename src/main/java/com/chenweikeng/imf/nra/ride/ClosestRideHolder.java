package com.chenweikeng.imf.nra.ride;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

public class ClosestRideHolder {
  private static final String COORDINATES_RESOURCE =
      "/assets/not-riding-alert/ride-coordinates.json";
  private static final List<RideCoordinate> COORDINATES = new ArrayList<>();
  private static RideName closestRide = null;

  private static final double Y_RANGE = 3.0;

  private static final Set<RideName> Y_RESTRICTED_RIDES =
      Set.of(RideName.DISNEYLAND_MONORAIL, RideName.FINDING_NEMO_SUBMARINE_VOYAGE);

  static {
    loadCoordinates();
  }

  private static void loadCoordinates() {
    try (InputStream is = ClosestRideHolder.class.getResourceAsStream(COORDINATES_RESOURCE)) {
      if (is == null) {
        NotRidingAlertClient.LOGGER.error(
            "Ride coordinates resource not found: {}", COORDINATES_RESOURCE);
        return;
      }
      InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
      List<CoordinateData> dataList =
          new Gson().fromJson(reader, new TypeToken<List<CoordinateData>>() {}.getType());
      if (dataList == null) {
        return;
      }
      for (CoordinateData data : dataList) {
        if (data.x == 0 && data.y == 0 && data.z == 0) {
          continue;
        }
        RideName ride = RideName.fromMatchString(data.ride);
        if (ride == null || ride == RideName.UNKNOWN) {
          NotRidingAlertClient.LOGGER.warn("Unknown ride in ride coordinates: {}", data.ride);
          continue;
        }
        if (ride.isSeasonal()) {
          continue;
        }
        COORDINATES.add(new RideCoordinate(ride, data.dimension, data.x, data.y, data.z));
      }
      NotRidingAlertClient.LOGGER.info(
          "Loaded {} ride coordinates for closest ride feature", COORDINATES.size());
    } catch (Exception e) {
      NotRidingAlertClient.LOGGER.error("Failed to load ride coordinates", e);
    }
  }

  public static void update(Minecraft client) {
    if (client == null || client.player == null || client.level == null) {
      closestRide = null;
      return;
    }
    if (COORDINATES.isEmpty()) {
      closestRide = null;
      return;
    }

    LocalPlayer player = client.player;
    String currentDimension = client.level.dimension().identifier().getPath();
    double px = player.getX();
    double py = player.getY();
    double pz = player.getZ();

    RideName nearest = null;
    double nearestDistSq = Double.MAX_VALUE;

    for (RideCoordinate coord : COORDINATES) {
      if (!coord.dimension.equals(currentDimension)) {
        continue;
      }
      if (Y_RESTRICTED_RIDES.contains(coord.ride) && Math.abs(py - coord.y) > Y_RANGE) {
        continue;
      }
      double dx = px - coord.x;
      double dy = py - coord.y;
      double dz = pz - coord.z;
      double distSq = dx * dx + dy * dy + dz * dz;
      if (distSq < nearestDistSq) {
        nearestDistSq = distSq;
        nearest = coord.ride;
      }
    }

    closestRide = nearest;
  }

  public static RideName getClosestRide() {
    return closestRide == null ? null : SeasonalRideSchedule.forDate(closestRide, LocalDate.now());
  }

  public static void reset() {
    closestRide = null;
  }

  private static class RideCoordinate {
    final RideName ride;
    final String dimension;
    final double x;
    final double y;
    final double z;

    RideCoordinate(RideName ride, String dimension, double x, double y, double z) {
      this.ride = ride;
      this.dimension = dimension;
      this.x = x;
      this.y = y;
      this.z = z;
    }
  }

  private static class CoordinateData {
    String ride;
    String dimension;
    double x;
    double y;
    double z;
  }
}
