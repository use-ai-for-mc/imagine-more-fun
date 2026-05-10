package com.chenweikeng.imf.nra.simulator.command;

import com.chenweikeng.imf.nra.ServerState;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.chenweikeng.imf.nra.ride.RideName;
import com.chenweikeng.imf.nra.simulator.GhostManager;
import com.chenweikeng.imf.nra.simulator.GhostRoster;
import com.chenweikeng.imf.nra.simulator.GhostSpec;
import com.chenweikeng.imf.nra.simulator.ProfileInjector;
import com.chenweikeng.imf.nra.simulator.SeatDebug;
import com.chenweikeng.imf.nra.simulator.vehicle.Vehicle;
import com.chenweikeng.imf.nra.simulator.vehicle.VehicleCatalog;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Step 1 debug command: {@code /imf-sim inject <name>}, {@code /imf-sim remove <name>}, {@code
 * /imf-sim clear}, {@code /imf-sim status}. Lets us toggle profile injection from chat to verify
 * the skin pipeline before any rendering is wired up.
 */
public final class SimDebugCommand {

  private SimDebugCommand() {}

  public static void register() {
    ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, registryAccess) ->
            dispatcher.register(
                ClientCommandManager.literal("imf-sim")
                    .requires(src -> ServerState.isImagineFunServer())
                    .then(
                        ClientCommandManager.literal("inject")
                            .then(
                                ClientCommandManager.argument("name", StringArgumentType.word())
                                    .suggests(SimDebugCommand::suggestNames)
                                    .executes(SimDebugCommand::doInject)))
                    .then(
                        ClientCommandManager.literal("remove")
                            .then(
                                ClientCommandManager.argument("name", StringArgumentType.word())
                                    .suggests(SimDebugCommand::suggestNames)
                                    .executes(SimDebugCommand::doRemove)))
                    .then(ClientCommandManager.literal("clear").executes(SimDebugCommand::doClear))
                    .then(
                        ClientCommandManager.literal("status").executes(SimDebugCommand::doStatus))
                    .then(
                        ClientCommandManager.literal("spawn")
                            .then(
                                ClientCommandManager.argument("name", StringArgumentType.word())
                                    .suggests(SimDebugCommand::suggestNames)
                                    .executes(SimDebugCommand::doSpawn)))
                    .then(
                        ClientCommandManager.literal("despawn")
                            .executes(SimDebugCommand::doDespawnAll))
                    .then(ClientCommandManager.literal("arm").executes(SimDebugCommand::doArm))
                    .then(
                        ClientCommandManager.literal("disarm").executes(SimDebugCommand::doDisarm))
                    .then(
                        ClientCommandManager.literal("seatdebug")
                            .executes(SimDebugCommand::doSeatDebugToggle))
                    .then(
                        ClientCommandManager.literal("vehicle-debug")
                            .executes(SimDebugCommand::doVehicleDebug))
                    .then(
                        ClientCommandManager.literal("list-vehicles")
                            .executes(SimDebugCommand::doListVehicles))
                    .then(
                        ClientCommandManager.literal("reload-vehicles")
                            .executes(SimDebugCommand::doReloadVehicles))));
  }

  private static CompletableFuture<Suggestions> suggestNames(
      CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder builder) {
    for (GhostSpec spec : GhostRoster.all()) {
      builder.suggest(spec.name());
    }
    return builder.buildFuture();
  }

  private static int doInject(CommandContext<FabricClientCommandSource> ctx) {
    String name = StringArgumentType.getString(ctx, "name");
    GhostSpec spec = GhostRoster.byName(name);
    if (spec == null) {
      ctx.getSource().sendError(Component.literal("Unknown ghost: " + name));
      return 0;
    }
    boolean injected = ProfileInjector.inject(spec);
    ctx.getSource()
        .sendFeedback(
            Component.literal(injected ? "Injected " + name : name + " was already injected"));
    return 1;
  }

  private static int doRemove(CommandContext<FabricClientCommandSource> ctx) {
    String name = StringArgumentType.getString(ctx, "name");
    GhostSpec spec = GhostRoster.byName(name);
    if (spec == null) {
      ctx.getSource().sendError(Component.literal("Unknown ghost: " + name));
      return 0;
    }
    boolean removed = ProfileInjector.remove(spec.uuid());
    ctx.getSource()
        .sendFeedback(Component.literal(removed ? "Removed " + name : name + " was not injected"));
    return 1;
  }

  private static int doClear(CommandContext<FabricClientCommandSource> ctx) {
    int count = 0;
    for (GhostSpec spec : GhostRoster.all()) {
      if (ProfileInjector.remove(spec.uuid())) count++;
    }
    ctx.getSource().sendFeedback(Component.literal("Removed " + count + " ghost(s)"));
    return 1;
  }

  private static int doStatus(CommandContext<FabricClientCommandSource> ctx) {
    StringBuilder sb = new StringBuilder("Simulator: armed=");
    sb.append(GhostManager.get().isArmed());
    sb.append("\nGhost roster:");
    for (GhostSpec spec : GhostRoster.all()) {
      sb.append("\n  ")
          .append(spec.name())
          .append(" — profile=")
          .append(ProfileInjector.isInjected(spec.uuid()) ? "INJECTED" : "not injected")
          .append(", entity=")
          .append(GhostManager.get().isSpawned(spec.uuid()) ? "SPAWNED" : "not spawned");
    }
    ctx.getSource().sendFeedback(Component.literal(sb.toString()));
    return 1;
  }

  private static int doArm(CommandContext<FabricClientCommandSource> ctx) {
    GhostManager.get().setArmed(true);
    ctx.getSource()
        .sendFeedback(
            Component.literal(
                "Armed. Mounting an ImagineFun ride will auto-fill empty seats with ghosts."));
    return 1;
  }

  private static int doDisarm(CommandContext<FabricClientCommandSource> ctx) {
    GhostManager.get().setArmed(false);
    ctx.getSource().sendFeedback(Component.literal("Disarmed. All ghosts despawned."));
    return 1;
  }

  private static int doSpawn(CommandContext<FabricClientCommandSource> ctx) {
    String name = StringArgumentType.getString(ctx, "name");
    GhostSpec spec = GhostRoster.byName(name);
    if (spec == null) {
      ctx.getSource().sendError(Component.literal("Unknown ghost: " + name));
      return 0;
    }
    String err = GhostManager.get().spawnInFrontOfPlayer(spec);
    if (err != null) {
      ctx.getSource().sendError(Component.literal("Spawn failed: " + err));
      return 0;
    }
    ctx.getSource().sendFeedback(Component.literal("Spawned " + name + " in front of you"));
    return 1;
  }

  private static int doDespawnAll(CommandContext<FabricClientCommandSource> ctx) {
    int n = GhostManager.get().despawnAll();
    ctx.getSource().sendFeedback(Component.literal("Despawned " + n + " ghost(s)"));
    return 1;
  }

  private static int doSeatDebugToggle(CommandContext<FabricClientCommandSource> ctx) {
    SeatDebug d = SeatDebug.get();
    d.setEnabled(!d.isEnabled());
    ctx.getSource()
        .sendFeedback(
            Component.literal(
                "SeatDebug "
                    + (d.isEnabled() ? "ENABLED" : "DISABLED")
                    + " — chat headers + full tables in latest.log"));
    return 1;
  }

  // ─── vehicle catalog (Phase A+B) ───────────────────────────────────────────────────────────────

  /** Limit for chat output so we don't spam pages of stands. */
  private static final int VEHICLE_DEBUG_LIST_CAP = 12;

  /** Search radius around the user when scanning candidate vehicle anchors. */
  private static final double VEHICLE_DEBUG_RADIUS = 10.0;

  private static int doVehicleDebug(CommandContext<FabricClientCommandSource> ctx) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null) {
      ctx.getSource().sendError(Component.literal("No player/level"));
      return 0;
    }

    // Resolve the current ride via NRA's scoreboard-derived state.
    RideName ride = CurrentRideHolder.getCurrentRide();
    String rideShort = (ride != null) ? ride.getShortName() : null;
    String rideDisplay = (ride != null) ? ride.getDisplayName() : "(none — not on a real ride)";

    StringBuilder sb = new StringBuilder("[VehicleDebug]\n");
    sb.append("  ride: ").append(rideDisplay);
    if (rideShort != null) sb.append("  (short=").append(rideShort).append(")");
    sb.append('\n');

    List<Vehicle> catalogForRide =
        rideShort == null ? List.of() : VehicleCatalog.forRide(rideShort);
    sb.append("  catalog: ")
        .append(catalogForRide.size())
        .append(" vehicle(s) registered for this ride\n");

    // Scan nearby armor stands once; collect head-slot info and any catalog matches.
    AABB box =
        mc.player
            .getBoundingBox()
            .inflate(VEHICLE_DEBUG_RADIUS, VEHICLE_DEBUG_RADIUS, VEHICLE_DEBUG_RADIUS);
    int totalScanned = 0;
    List<MatchedAnchor> matches = new java.util.ArrayList<>();
    Map<String, AnchorCandidate> candidatesByItemKey = new LinkedHashMap<>();
    for (Entity e : mc.level.entitiesForRendering()) {
      if (!(e instanceof ArmorStand stand)) continue;
      if (!box.contains(stand.position())) continue;
      totalScanned++;
      ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
      if (head.isEmpty()) continue;
      String itemId = BuiltInRegistries.ITEM.getKey(head.getItem()).toString();
      int damage = head.getDamageValue();
      String key = itemId + "@" + damage;
      AnchorCandidate prev = candidatesByItemKey.get(key);
      double distSq = mc.player.distanceToSqr(stand.getX(), stand.getY(), stand.getZ());
      if (prev == null || distSq < prev.distSq) {
        candidatesByItemKey.put(
            key,
            new AnchorCandidate(
                itemId, damage, stand.getId(), stand.getX(), stand.getY(), stand.getZ(), distSq));
      }
      Vehicle matched = VehicleCatalog.matchStand(rideShort, stand);
      if (matched != null) {
        matches.add(new MatchedAnchor(matched, stand, Math.sqrt(distSq)));
      }
    }
    sb.append("  scanned: ")
        .append(totalScanned)
        .append(" stand(s) within ")
        .append((int) VEHICLE_DEBUG_RADIUS)
        .append(" blocks\n");

    if (matches.isEmpty()) {
      sb.append("  matched: (none)\n");
    } else {
      // Sort by distance so the closest is first.
      matches.sort((a, b) -> Double.compare(a.dist, b.dist));
      sb.append("  matched: ").append(matches.size()).append(" anchor(s)\n");
      for (MatchedAnchor m : matches) {
        sb.append("    ")
            .append(m.vehicle.id())
            .append("  stand=")
            .append(m.stand.getId())
            .append("  pos=(")
            .append(
                String.format("%.2f, %.2f, %.2f", m.stand.getX(), m.stand.getY(), m.stand.getZ()))
            .append(")  yaw=")
            .append(String.format("%.1f", m.stand.getYRot()))
            .append("  dist=")
            .append(String.format("%.2f", m.dist))
            .append('\n');
      }
    }

    if (!candidatesByItemKey.isEmpty()) {
      sb.append("  unique head items observed (potential anchor candidates):\n");
      int count = 0;
      for (AnchorCandidate c : candidatesByItemKey.values()) {
        if (count++ >= VEHICLE_DEBUG_LIST_CAP) {
          sb.append("    … (")
              .append(candidatesByItemKey.size() - VEHICLE_DEBUG_LIST_CAP)
              .append(" more)\n");
          break;
        }
        sb.append("    itemId=")
            .append(c.itemId)
            .append("  damage=")
            .append(c.damage)
            .append("  closest stand=")
            .append(c.standId)
            .append("  dist=")
            .append(String.format("%.2f", Math.sqrt(c.distSq)))
            .append('\n');
      }
    }

    ctx.getSource().sendFeedback(Component.literal(sb.toString()));
    return 1;
  }

  private record AnchorCandidate(
      String itemId, int damage, int standId, double x, double y, double z, double distSq) {}

  private record MatchedAnchor(Vehicle vehicle, ArmorStand stand, double dist) {}

  private static int doListVehicles(CommandContext<FabricClientCommandSource> ctx) {
    Map<String, List<Vehicle>> snapshot = VehicleCatalog.snapshot();
    StringBuilder sb = new StringBuilder("[VehicleCatalog]\n");
    sb.append("  loaded from: ").append(VehicleCatalog.lastLoadedFrom()).append('\n');
    if (snapshot.isEmpty()) {
      sb.append("  (empty — create vehicles.json at the path above)\n");
    } else {
      for (var entry : snapshot.entrySet()) {
        sb.append("  ").append(entry.getKey()).append(":\n");
        for (Vehicle v : entry.getValue()) {
          sb.append("    ")
              .append(v.id())
              .append("  match=(itemId=")
              .append(v.match().itemId())
              .append(", damage=")
              .append(v.match().damage())
              .append(")\n");
        }
      }
    }
    ctx.getSource().sendFeedback(Component.literal(sb.toString()));
    return 1;
  }

  private static int doReloadVehicles(CommandContext<FabricClientCommandSource> ctx) {
    VehicleCatalog.reload();
    Map<String, List<Vehicle>> snapshot = VehicleCatalog.snapshot();
    int total = snapshot.values().stream().mapToInt(List::size).sum();
    ctx.getSource()
        .sendFeedback(
            Component.literal(
                "VehicleCatalog reloaded: "
                    + total
                    + " vehicle(s) across "
                    + snapshot.size()
                    + " ride(s) — "
                    + VehicleCatalog.lastLoadedFrom()));
    return 1;
  }
}
