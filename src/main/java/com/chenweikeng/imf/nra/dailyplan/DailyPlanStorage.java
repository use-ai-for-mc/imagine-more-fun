package com.chenweikeng.imf.nra.dailyplan;

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

public final class DailyPlanStorage {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private DailyPlanStorage() {}

  public static DailyPlan load() {
    Path path = ImfStorage.nraDailyPlan();
    File file = path.toFile();
    if (!file.exists()) {
      return null;
    }
    try (FileReader reader = new FileReader(file)) {
      return GSON.fromJson(reader, DailyPlan.class);
    } catch (IOException e) {
      return null;
    }
  }

  public static void save(DailyPlan plan) {
    if (plan == null) {
      return;
    }
    Path path = ImfStorage.nraDailyPlan();
    try {
      Files.createDirectories(path.getParent());
      try (FileWriter writer = new FileWriter(path.toFile())) {
        GSON.toJson(plan, writer);
      }
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.error("Failed to save daily plan", e);
    }
  }
}
