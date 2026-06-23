package com.chenweikeng.imf.nra.config;

import com.chenweikeng.imf.ImfFileIO;
import com.chenweikeng.imf.ImfStorage;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.nio.file.Path;

public class ModConfig {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Path CONFIG_PATH = ImfStorage.nraConfig();

  public static ConfigSetting currentSetting = new ConfigSetting();

  public static void load() {
    File configFile = CONFIG_PATH.toFile();
    if (!configFile.exists()) {
      currentSetting = new ConfigSetting();
      return;
    }

    currentSetting =
        ImfFileIO.readJson(
            CONFIG_PATH, GSON, ConfigSetting.class, NotRidingAlertClient.LOGGER, "NRA config");
    if (currentSetting == null) {
      currentSetting = new ConfigSetting();
    }
  }

  public static void save() {
    if (currentSetting == null) {
      return;
    }
    ImfFileIO.writeJsonAtomic(
        CONFIG_PATH, GSON, currentSetting, NotRidingAlertClient.LOGGER, "NRA config");
  }

  public static void resetToDefaults() {
    if (currentSetting != null) {
      currentSetting.resetToDefaults();
    }
    save();
  }
}
