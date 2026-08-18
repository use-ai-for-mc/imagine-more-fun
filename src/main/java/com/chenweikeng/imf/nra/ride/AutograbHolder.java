package com.chenweikeng.imf.nra.ride;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

public class AutograbHolder {
  private static final String REGIONS_RESOURCE = "/assets/not-riding-alert/autograb-regions.json";
  private static final List<AutograbRegion> REGIONS = new ArrayList<>();
  private static final Set<RideName> AUTOGRAB_RIDES = new HashSet<>();
  private static ClientLevel cachedLevel;
  private static LocalPlayer cachedPlayer;
  private static long cachedGameTime;
  private static double cachedX;
  private static double cachedY;
  private static double cachedZ;
  private static RideName cachedRide;
  private static boolean locationCacheValid;

  static {
    loadRegions();
  }

  private static void loadRegions() {
    try (InputStream is = AutograbHolder.class.getResourceAsStream(REGIONS_RESOURCE)) {
      if (is == null) {
        NotRidingAlertClient.LOGGER.error(
            "Autograb regions resource not found: {}", REGIONS_RESOURCE);
        return;
      }
      InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
      List<RegionData> dataList =
          new Gson().fromJson(reader, new TypeToken<List<RegionData>>() {}.getType());
      if (dataList == null) {
        return;
      }
      for (RegionData data : dataList) {
        RideName ride = RideName.fromMatchString(data.ride);
        if (ride == null || ride == RideName.UNKNOWN) {
          NotRidingAlertClient.LOGGER.warn("Unknown ride in autograb regions: {}", data.ride);
          continue;
        }
        Predicate<Minecraft> filter = createDimensionFilter(data.dimension);
        Point center = new Point(data.center.x, data.center.z);
        Point[] points = new Point[data.points.size()];
        for (int i = 0; i < data.points.size(); i++) {
          PointData pd = data.points.get(i);
          points[i] = new Point(pd.x, pd.z);
        }
        REGIONS.add(new AutograbRegion(ride, filter, center, data.y, points, data.dimension));
        AUTOGRAB_RIDES.add(ride);
      }
    } catch (Exception e) {
      NotRidingAlertClient.LOGGER.error("Failed to load autograb regions", e);
    }
  }

  private static Predicate<Minecraft> createDimensionFilter(String dimension) {
    return client -> {
      if (client.level == null) return false;
      String path = client.level.dimension().identifier().getPath();
      return dimension.equals(path);
    };
  }

  public static RideName getRideAtLocation(Minecraft client) {
    if (client == null || client.player == null || client.level == null) {
      resetLocationCache();
      return null;
    }

    LocalPlayer player = client.player;
    ClientLevel level = client.level;
    long gameTime = level.getGameTime();
    double x = player.getX();
    double y = player.getY();
    double z = player.getZ();
    if (locationCacheValid
        && cachedLevel == level
        && cachedPlayer == player
        && cachedGameTime == gameTime
        && Double.compare(cachedX, x) == 0
        && Double.compare(cachedY, y) == 0
        && Double.compare(cachedZ, z) == 0) {
      return cachedRide;
    }

    RideName ride = null;
    for (AutograbRegion region : REGIONS) {
      if (region.contains(client)) {
        ride = region.getRide();
        break;
      }
    }
    cachedLevel = level;
    cachedPlayer = player;
    cachedGameTime = gameTime;
    cachedX = x;
    cachedY = y;
    cachedZ = z;
    cachedRide = ride;
    locationCacheValid = true;
    return ride;
  }

  public static void resetLocationCache() {
    cachedLevel = null;
    cachedPlayer = null;
    cachedRide = null;
    locationCacheValid = false;
  }

  public static boolean hasAutograb(RideName ride) {
    return AUTOGRAB_RIDES.contains(ride);
  }

  public static List<AutograbRegion> regions() {
    return REGIONS;
  }

  private static boolean pointInPolygon(double x, double z, Point[] polygon) {
    int n = polygon.length;
    boolean inside = false;
    for (int i = 0, j = n - 1; i < n; j = i++) {
      double xi = polygon[i].x, zi = polygon[i].z;
      double xj = polygon[j].x, zj = polygon[j].z;
      if (((zi > z) != (zj > z)) && (x < (xj - xi) * (z - zi) / (zj - zi) + xi)) {
        inside = !inside;
      }
    }
    return inside;
  }

  private static Point[] ensureCounterClockwise(Point[] points) {
    double area = calculateSignedArea(points);
    if (area < 0) {
      Point[] reversed = new Point[points.length];
      for (int i = 0; i < points.length; i++) {
        reversed[i] = points[points.length - 1 - i];
      }
      return reversed;
    }
    return points;
  }

  private static double calculateSignedArea(Point[] points) {
    double area = 0;
    int n = points.length;
    for (int i = 0; i < n; i++) {
      int j = (i + 1) % n;
      area += (points[j].x - points[i].x) * (points[j].z + points[i].z);
    }
    return area / 2;
  }

  public static class AutograbRegion {
    private final RideName ride;
    private final Predicate<Minecraft> filter;
    private final Point center;
    private final double y;
    private final Point[] points;
    private final String dimension;

    AutograbRegion(
        RideName ride,
        Predicate<Minecraft> filter,
        Point center,
        double y,
        Point[] points,
        String dimension) {
      this.ride = ride;
      this.filter = filter;
      this.center = center;
      this.y = y;
      this.points = ensureCounterClockwise(points);
      this.dimension = dimension;
    }

    public RideName getRide() {
      return ride;
    }

    public Predicate<Minecraft> filter() {
      return filter;
    }

    public Point center() {
      return center;
    }

    public double y() {
      return y;
    }

    public Point[] points() {
      return points;
    }

    public boolean contains(Minecraft client) {
      if (!this.filter.test(client)) {
        return false;
      }
      LocalPlayer player = client.player;
      if (player == null) {
        return false;
      }
      double x = player.getX();
      double y = player.getY();
      double z = player.getZ();

      if (Math.abs(y - this.y) > 3) {
        return false;
      }
      double dx = x - center.x;
      double dz = z - center.z;
      double dy = y - this.y;
      if (Math.sqrt(dx * dx + dz * dz + dy * dy) > 100) {
        return false;
      }
      return pointInPolygon(x, z, points);
    }
  }

  public static class Point {
    public final double x;
    public final double z;

    public Point(double x, double z) {
      this.x = x;
      this.z = z;
    }
  }

  private static class RegionData {
    String ride;
    String dimension;
    PointData center;
    double y;
    List<PointData> points;
  }

  private static class PointData {
    double x;
    double z;
  }
}
