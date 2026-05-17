package com.chenweikeng.imf.pim;

import com.chenweikeng.imf.pim.command.PimCommand;
import com.chenweikeng.imf.pim.tracker.BossBarTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PimClient implements ClientModInitializer {

  public static final String MOD_ID = "pim";
  public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
  private static boolean isImagineFunServer = false;

  @Override
  public void onInitializeClient() {
    ClientPlayConnectionEvents.JOIN.register(
        (handler, sender, client) -> {
          onJoin(client);
        });

    ClientPlayConnectionEvents.DISCONNECT.register(
        (handler, client) -> {
          onDisconnect();
        });

    PimCommand.registerCommands();
    BossBarTracker.getInstance();
  }

  public static boolean isImagineFunServer() {
    return isImagineFunServer;
  }

  public static void onJoin(Minecraft client) {
    if (client.getCurrentServer() == null || client.getCurrentServer().ip == null) {
      isImagineFunServer = false;
      return;
    }

    String serverIp = client.getCurrentServer().ip.toLowerCase();
    String host = serverIp.split(":")[0];
    isImagineFunServer = host.endsWith(".imaginefun.net");
  }

  public static void onDisconnect() {
    isImagineFunServer = false;
  }
}
