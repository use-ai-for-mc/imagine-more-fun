package com.chenweikeng.imf.nra.config;

import com.chenweikeng.imf.ImfStorage;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
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

    try (FileReader reader = new FileReader(configFile)) {
      currentSetting = GSON.fromJson(reader, ConfigSetting.class);
      if (currentSetting == null) {
        currentSetting = new ConfigSetting();
      }
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.warn("Failed to load NRA config, using defaults", e);
      currentSetting = new ConfigSetting();
    }
  }

  public static void save() {
    if (currentSetting == null) {
      return;
    }
    try {
      Files.createDirectories(CONFIG_PATH.getParent());
      try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
        GSON.toJson(currentSetting, writer);
      }
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.error("Failed to save NRA config", e);
    }
  }

  public static void resetToDefaults() {
    if (currentSetting != null) {
      currentSetting.resetToDefaults();
    }
    save();
  }
}
