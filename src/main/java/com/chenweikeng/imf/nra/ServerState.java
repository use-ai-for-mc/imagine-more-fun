package com.chenweikeng.imf.nra;

import com.chenweikeng.imf.nra.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerState {
  private static final Logger LOGGER = LoggerFactory.getLogger(NotRidingAlertClient.MOD_ID);
  private static boolean isImagineFunServer = false;
  private static float savedMusicVolume = -1f;

  public static boolean isImagineFunServer() {
    return isImagineFunServer && ModConfig.currentSetting.globalEnable;
  }

  public static void onJoin(Minecraft client) {
    if (client.getCurrentServer() == null || client.getCurrentServer().ip == null) {
      isImagineFunServer = false;
      return;
    }

    String serverIp = client.getCurrentServer().ip.toLowerCase();
    String host = serverIp.split(":")[0];
    isImagineFunServer = host.endsWith(".imaginefun.net");

    if (isImagineFunServer) {
      muteMusicVolume(client);
    }
  }

  public static void onDisconnect() {
    restoreMusicVolume();
    isImagineFunServer = false;
  }

  private static void muteMusicVolume(Minecraft client) {
    float current = client.options.getSoundSourceVolume(SoundSource.MUSIC);
    if (current > 0f) {
      savedMusicVolume = current;
      client.options.getSoundSourceOptionInstance(SoundSource.MUSIC).set(0.0);
    }
  }

  private static void restoreMusicVolume() {
    if (savedMusicVolume >= 0f) {
      Minecraft client = Minecraft.getInstance();
      client.options.getSoundSourceOptionInstance(SoundSource.MUSIC).set((double) savedMusicVolume);
      savedMusicVolume = -1f;
    }
  }
}
