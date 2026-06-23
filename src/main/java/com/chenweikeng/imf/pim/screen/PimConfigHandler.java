package com.chenweikeng.imf.pim.screen;

import com.chenweikeng.imf.ImfFileIO;
import com.chenweikeng.imf.pim.PimClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;

public class PimConfigHandler {
  private static PimConfigHandler instance;
  private final Gson gson;
  private final File configFile;
  private double fmvDiscount = 1.0;

  private PimConfigHandler() {
    this.gson = new GsonBuilder().setPrettyPrinting().create();
    this.configFile = new File("config/pim_config.json");
    load();
  }

  public static PimConfigHandler getInstance() {
    if (instance == null) {
      instance = new PimConfigHandler();
    }
    return instance;
  }

  public double getFmvDiscount() {
    return fmvDiscount;
  }

  public void setFmvDiscount(double discount) {
    this.fmvDiscount = discount;
    save();
  }

  private void save() {
    ConfigData data = new ConfigData();
    data.fmvDiscount = fmvDiscount;
    ImfFileIO.writeJsonAtomic(configFile.toPath(), gson, data, PimClient.LOGGER, "PIM config");
  }

  private void load() {
    if (!configFile.exists()) {
      save();
      return;
    }

    ConfigData data =
        ImfFileIO.readJson(
            configFile.toPath(), gson, ConfigData.class, PimClient.LOGGER, "PIM config");
    if (data != null && data.fmvDiscount > 0) {
      fmvDiscount = data.fmvDiscount;
    }
  }

  private static class ConfigData {
    public double fmvDiscount = 1.0;
  }
}
