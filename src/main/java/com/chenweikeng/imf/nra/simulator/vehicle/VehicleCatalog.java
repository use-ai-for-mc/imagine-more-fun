package com.chenweikeng.imf.nra.simulator.vehicle;

import com.chenweikeng.imf.ImfClient;
import com.chenweikeng.imf.ImfStorage;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;

/**
 * Loads the user-curated {@code vehicles.json} into memory and provides matching against an armor
 * stand for a given ride.
 *
 * <p>JSON shape:
 *
 * <pre>{@code
 * {
 *   "rides": {
 *     "mainst": {
 *       "vehicles": [
 *         { "id": "main_street_omnibus", "match": { "itemId": "minecraft:diamond_pickaxe", "damage": 442 } }
 *       ]
 *     }
 *   }
 * }
 * }</pre>
 *
 * Keys under {@code rides} are {@code RideName.getShortName()} (e.g. {@code mainst}, {@code dlrr}).
 */
public final class VehicleCatalog {

  private static final Map<String, List<Vehicle>> BY_RIDE_SHORT_NAME = new LinkedHashMap<>();
  private static Path lastLoadedFrom;

  private VehicleCatalog() {}

  /** Reload the catalog from disk. Safe to call repeatedly; replaces existing in-memory state. */
  public static synchronized void reload() {
    Path path = ImfStorage.simulatorVehicles();
    lastLoadedFrom = path;
    BY_RIDE_SHORT_NAME.clear();
    if (!Files.exists(path)) {
      ImfClient.LOGGER.info(
          "VehicleCatalog: no vehicles.json at {}, starting empty (use /imf-sim vehicle-debug to"
              + " discover head-slot items on nearby stands)",
          path);
      return;
    }
    try {
      String json = Files.readString(path);
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      JsonObject rides = root.getAsJsonObject("rides");
      if (rides == null) {
        ImfClient.LOGGER.warn("VehicleCatalog: {} missing 'rides' object; ignoring", path);
        return;
      }
      for (Map.Entry<String, JsonElement> rideEntry : rides.entrySet()) {
        String shortName = rideEntry.getKey();
        JsonObject rideObj = rideEntry.getValue().getAsJsonObject();
        if (!rideObj.has("vehicles")) continue;
        List<Vehicle> vehicles = new ArrayList<>();
        for (JsonElement vEl : rideObj.getAsJsonArray("vehicles")) {
          JsonObject vObj = vEl.getAsJsonObject();
          String id = vObj.get("id").getAsString();
          JsonObject m = vObj.getAsJsonObject("match");
          String itemId = m.get("itemId").getAsString();
          int damage = m.get("damage").getAsInt();
          vehicles.add(new Vehicle(id, new VehicleMatch(itemId, damage)));
        }
        BY_RIDE_SHORT_NAME.put(shortName, vehicles);
      }
      int total = BY_RIDE_SHORT_NAME.values().stream().mapToInt(List::size).sum();
      ImfClient.LOGGER.info(
          "VehicleCatalog: loaded {} vehicle(s) across {} ride(s) from {}",
          total,
          BY_RIDE_SHORT_NAME.size(),
          path);
    } catch (IOException | RuntimeException e) {
      ImfClient.LOGGER.error("VehicleCatalog: failed to load {}", path, e);
    }
  }

  /** Returns the candidate vehicles for the given ride short-name, or empty list if none. */
  public static List<Vehicle> forRide(String rideShortName) {
    if (rideShortName == null) return List.of();
    return BY_RIDE_SHORT_NAME.getOrDefault(rideShortName, List.of());
  }

  /**
   * Find the first vehicle in the ride's catalog that matches the head-slot item of the given armor
   * stand. Returns null if no match (or no catalog entry, or the stand has nothing on its head).
   */
  public static Vehicle matchStand(String rideShortName, ArmorStand stand) {
    List<Vehicle> candidates = forRide(rideShortName);
    if (candidates.isEmpty()) return null;
    ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
    if (head.isEmpty()) return null;
    String itemId = BuiltInRegistries.ITEM.getKey(head.getItem()).toString();
    int damage = head.getDamageValue();
    for (Vehicle v : candidates) {
      if (v.match().matches(itemId, damage)) return v;
    }
    return null;
  }

  public static Map<String, List<Vehicle>> snapshot() {
    Map<String, List<Vehicle>> copy = new LinkedHashMap<>();
    for (var entry : BY_RIDE_SHORT_NAME.entrySet()) {
      copy.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
    }
    return Collections.unmodifiableMap(copy);
  }

  public static Path lastLoadedFrom() {
    return lastLoadedFrom;
  }
}
