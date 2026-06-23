package com.chenweikeng.imf.nra.wizard;

import com.chenweikeng.imf.ImfFileIO;
import com.chenweikeng.imf.ImfStorage;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public class TutorialManager {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Path CONFIG_PATH = ImfStorage.nraTutorial();

  private static TutorialManager instance;

  private TutorialState state = TutorialState.NOT_STARTED;
  private boolean completed = false;
  private String completedVersion = null;

  private TutorialManager() {
    load();
  }

  public static TutorialManager getInstance() {
    if (instance == null) {
      instance = new TutorialManager();
    }
    return instance;
  }

  public static String getCurrentModVersion() {
    return FabricLoader.getInstance()
        .getModContainer(NotRidingAlertClient.MOD_ID)
        .map(container -> container.getMetadata().getVersion().getFriendlyString())
        .orElse("unknown");
  }

  public boolean shouldStartTutorial() {
    return state == TutorialState.NOT_STARTED && NotRidingAlertClient.isImagineFunServer();
  }

  public boolean isTutorialActive() {
    return state.isActive();
  }

  public boolean isCompletedForCurrentVersion() {
    if (!completed) {
      return false;
    }
    String currentVersion = getCurrentModVersion();
    return currentVersion.equals(completedVersion);
  }

  public TutorialState getState() {
    return state;
  }

  public int getCurrentPageIndex() {
    return state.getPageIndex();
  }

  public void advanceToNextPage() {
    if (state != TutorialState.FINISHED) {
      state = state.getNext();
      if (state == TutorialState.FINISHED) {
        completed = true;
        save();
      }
    }
  }

  public void goToPage(int pageIndex) {
    if (!NotRidingAlertClient.isImagineFunServer()) {
      return;
    }
    TutorialState newState = TutorialState.fromPageIndex(pageIndex);
    if (newState.isActive()) {
      state = newState;
      save();
    }
  }

  public void finishTutorial() {
    state = TutorialState.FINISHED;
    completed = true;
    completedVersion = getCurrentModVersion();
    save();
  }

  public void resetTutorial() {
    state = TutorialState.NOT_STARTED;
  }

  public void load() {
    File configFile = CONFIG_PATH.toFile();
    if (!configFile.exists()) {
      return;
    }

    TutorialData data =
        ImfFileIO.readJson(
            CONFIG_PATH, GSON, TutorialData.class, NotRidingAlertClient.LOGGER, "tutorial state");
    if (data != null) {
      this.completed = data.completed;
      this.completedVersion = data.completedVersion;
    }
  }

  public void save() {
    try {
      TutorialData data = new TutorialData();
      data.completed = this.completed;
      data.completedVersion = this.completedVersion;

      ImfFileIO.writeJsonAtomic(
          CONFIG_PATH, GSON, data, NotRidingAlertClient.LOGGER, "tutorial state");
    } catch (RuntimeException e) {
      NotRidingAlertClient.LOGGER.warn("Failed to save tutorial state", e);
    }
  }

  private static class TutorialData {
    boolean completed;
    String completedVersion;
  }
}
