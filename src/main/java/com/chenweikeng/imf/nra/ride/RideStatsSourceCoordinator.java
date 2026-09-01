package com.chenweikeng.imf.nra.ride;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import java.lang.reflect.InvocationTargetException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** Chooses the optional ImagineFun API source while preserving the legacy /ridestats parser. */
public final class RideStatsSourceCoordinator {
  private static final String UTILS_MOD_ID = "imaginefunutils";
  private static final String BRIDGE_CLASS =
      "com.chenweikeng.imf.nra.compat.ImagineFunUtilsRideDataSource";

  private static ApiBridge apiBridge;
  private static boolean apiPreferred;
  private static boolean apiSnapshotReady;

  private RideStatsSourceCoordinator() {}

  public static void initialize() {
    if (!FabricLoader.getInstance().isModLoaded(UTILS_MOD_ID)) {
      NotRidingAlertClient.LOGGER.info(
          "ImagineFunUtils is not installed; keeping legacy /ridestats ride-count capture");
      return;
    }

    try {
      Class<?> bridgeType = Class.forName(BRIDGE_CLASS);
      apiBridge = (ApiBridge) bridgeType.getDeclaredConstructor().newInstance();
      apiBridge.initialize();
      apiPreferred = true;
      NotRidingAlertClient.LOGGER.info(
          "ImagineFunUtils detected; server API ride-count sync is preferred");
    } catch (ClassNotFoundException
        | InstantiationException
        | IllegalAccessException
        | NoSuchMethodException
        | InvocationTargetException
        | LinkageError e) {
      apiBridge = null;
      apiPreferred = false;
      NotRidingAlertClient.LOGGER.warn(
          "ImagineFunUtils API bridge is unavailable; falling back to /ridestats", e);
    }
  }

  public static void onJoin(Minecraft client) {
    apiSnapshotReady = false;
    if (apiBridge != null) {
      apiBridge.onJoin(client);
    }
  }

  public static void onDisconnect() {
    apiSnapshotReady = false;
    if (apiBridge != null) {
      apiBridge.onDisconnect();
    }
  }

  public static void tick(Minecraft client) {
    if (apiBridge != null) {
      apiBridge.tick(client);
    }
  }

  public static boolean isApiPreferred() {
    return apiPreferred;
  }

  public static boolean isApiSnapshotReady() {
    return apiSnapshotReady;
  }

  /**
   * Manual /ridestats data remains authoritative even after a successful API snapshot.
   *
   * <p>The API is the preferred automatic source, but it can lag behind the in-game statistics menu
   * around a ride completion. Keeping the menu parser active lets an explicit /ridestats request
   * immediately reconcile the local count.
   */
  public static boolean shouldCaptureLegacyRideStats() {
    return true;
  }

  public static void markApiSnapshotReady() {
    apiSnapshotReady = true;
  }

  /** Internal bridge deliberately contains no ImagineFunUtils types. */
  public interface ApiBridge {
    void initialize();

    void onJoin(Minecraft client);

    void onDisconnect();

    void tick(Minecraft client);
  }
}
