package com.chenweikeng.imf.nra.config.profile;

import com.chenweikeng.imf.ImfFileIO;
import com.chenweikeng.imf.ImfStorage;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class HistoryStorage {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Path STORAGE_PATH = ImfStorage.nraHistory();
  private static final int CURRENT_VERSION = 1;

  private HistoryStorage() {}

  public static class HistoryStorageData {
    public int version;
    public List<HistoryEntry> entries;

    public HistoryStorageData() {
      this.version = CURRENT_VERSION;
      this.entries = new ArrayList<>();
    }

    public HistoryStorageData(int version, List<HistoryEntry> entries) {
      this.version = version;
      this.entries = entries != null ? entries : new ArrayList<>();
    }
  }

  public static HistoryStorageData load() {
    File storageFile = STORAGE_PATH.toFile();
    if (!storageFile.exists()) {
      return new HistoryStorageData();
    }

    HistoryStorageData data =
        ImfFileIO.readJson(
            STORAGE_PATH,
            GSON,
            HistoryStorageData.class,
            NotRidingAlertClient.LOGGER,
            "history storage");
    if (data == null) {
      return new HistoryStorageData();
    }
    if (data.entries == null) {
      data.entries = new ArrayList<>();
    }
    data.entries.removeIf(e -> e == null || e.data == null);
    return data;
  }

  public static void save(HistoryStorageData data) {
    if (data == null) {
      return;
    }
    ImfFileIO.writeJsonAtomic(
        STORAGE_PATH, GSON, data, NotRidingAlertClient.LOGGER, "history storage");
  }
}
