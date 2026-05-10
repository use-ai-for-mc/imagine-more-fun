package com.chenweikeng.imf.nra.simulator;

import com.chenweikeng.imf.ImfClient;
import com.chenweikeng.imf.nra.simulator.command.SimDebugCommand;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

// GhostManager is in the same package; no import needed.

/**
 * Entrypoint for the player-simulator subsystem. Step 1 only registers the debug command — no
 * rendering, no tick loop yet.
 *
 * <p>Wrapped in a try/catch so a simulator failure can never break NRA / PIM / SkinCache.
 */
public final class SimulatorClient {

  private SimulatorClient() {}

  public static void init() {
    try {
      SimDebugCommand.register();
      GhostManager.init();
      SeatDebug.init();
      com.chenweikeng.imf.nra.simulator.vehicle.VehicleCatalog.reload();
      registerJoinReminder();
      ImfClient.LOGGER.info("Simulator (step 5: vehicle catalog) initialized");
    } catch (RuntimeException e) {
      ImfClient.LOGGER.error("Simulator init failed; continuing", e);
    }
  }

  /**
   * On every server join, drop a chat reminder that the simulator subsystem is still development
   * code — including the {@link SeatDebug} diagnostic helper. Remove this hook (and the {@code
   * SeatDebug} class itself) before any public release.
   */
  private static void registerJoinReminder() {
    ClientPlayConnectionEvents.JOIN.register(
        (handler, sender, client) ->
            client.execute(
                () -> {
                  Minecraft mc = Minecraft.getInstance();
                  if (mc.player == null) return;
                  mc.player.displayClientMessage(
                      Component.literal(
                              "⚠ IMF Simulator (DEV BUILD) — SeatDebug toggle: /imf-sim seatdebug"
                                  + " — DO NOT RELEASE")
                          .withStyle(ChatFormatting.GOLD),
                      false);
                  mc.player.displayClientMessage(
                      Component.literal(
                              "  armed="
                                  + GhostManager.get().isArmed()
                                  + ", seatdebug="
                                  + SeatDebug.get().isEnabled())
                          .withStyle(ChatFormatting.GRAY),
                      false);
                }));
  }
}
