package com.chenweikeng.imf.nra.dailyplan;

import com.chenweikeng.imf.ImfFileIO;
import com.chenweikeng.imf.ImfStorage;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
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
    return ImfFileIO.readJson(
        path, GSON, DailyPlan.class, NotRidingAlertClient.LOGGER, "daily plan");
  }

  public static void save(DailyPlan plan) {
    if (plan == null) {
      return;
    }
    Path path = ImfStorage.nraDailyPlan();
    ImfFileIO.writeJsonAtomic(path, GSON, plan, NotRidingAlertClient.LOGGER, "daily plan");
  }
}
