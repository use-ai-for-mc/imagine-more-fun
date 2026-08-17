package com.chenweikeng.imf.nra.wizard;

import com.chenweikeng.imf.ImfClient;
import com.chenweikeng.imf.ImfFileIO;
import com.chenweikeng.imf.ImfStorage;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public class TutorialManager {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final int CURRENT_TUTORIAL_VERSION = 1;

  private static TutorialManager instance;

  private final Path configPath;
  private final Supplier<String> currentModVersion;
  private final BooleanSupplier imagineFunServer;
  private final Logger logger;

  private TutorialState state = TutorialState.NOT_STARTED;
  private boolean completed = false;
  private String completedVersion = null;
  private int completedTutorialVersion = 0;

  private TutorialManager() {
    this(
        ImfStorage.nraTutorial(),
        TutorialManager::getCurrentModVersion,
        NotRidingAlertClient::isImagineFunServer,
        NotRidingAlertClient.LOGGER);
  }

  TutorialManager(
      Path configPath,
      Supplier<String> currentModVersion,
      BooleanSupplier imagineFunServer,
      Logger logger) {
    this.configPath = configPath;
    this.currentModVersion = currentModVersion;
    this.imagineFunServer = imagineFunServer;
    this.logger = logger;
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
        .getModContainer(ImfClient.MOD_ID)
        .map(container -> container.getMetadata().getVersion().getFriendlyString())
        .orElse("unknown");
  }

  public boolean shouldStartTutorial() {
    return state == TutorialState.NOT_STARTED
        && !isCompletedForCurrentTutorial()
        && imagineFunServer.getAsBoolean();
  }

  public boolean isTutorialActive() {
    return state.isActive();
  }

  public boolean isCompletedForCurrentTutorial() {
    return completed && completedTutorialVersion >= CURRENT_TUTORIAL_VERSION;
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
        markCompleted();
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
    markCompleted();
  }

  private void markCompleted() {
    completed = true;
    completedVersion = currentModVersion.get();
    completedTutorialVersion = CURRENT_TUTORIAL_VERSION;
    save();
  }

  public void resetTutorial() {
    state = TutorialState.NOT_STARTED;
  }

  public void load() {
    TutorialData data =
        ImfFileIO.readJson(configPath, GSON, TutorialData.class, logger, "tutorial state");
    if (data != null) {
      this.completed = data.completed;
      this.completedVersion = data.completedVersion;
      this.completedTutorialVersion =
          data.completedTutorialVersion == null ? 0 : data.completedTutorialVersion;

      if (completed && completedTutorialVersion <= 0) {
        migrateLegacyCompletion();
      }

      if (isCompletedForCurrentTutorial()) {
        state = TutorialState.FINISHED;
      }
    }
  }

  private void migrateLegacyCompletion() {
    String legacyCompletedVersion = completedVersion;
    String actualVersion = currentModVersion.get();
    if (isUnknownVersion(completedVersion) && !isUnknownVersion(actualVersion)) {
      completedVersion = actualVersion;
    }
    completedTutorialVersion = CURRENT_TUTORIAL_VERSION;
    if (save()) {
      logger.info(
          "Migrated legacy tutorial completion from mod version {} to tutorial version {}",
          legacyCompletedVersion,
          CURRENT_TUTORIAL_VERSION);
    } else {
      logger.warn(
          "Accepted legacy tutorial completion for this session but could not persist its migration");
    }
  }

  private static boolean isUnknownVersion(String version) {
    return version == null || version.isBlank() || "unknown".equalsIgnoreCase(version);
  }

  public boolean save() {
    try {
      TutorialData data = new TutorialData();
      data.completed = this.completed;
      data.completedVersion = this.completedVersion;
      data.completedTutorialVersion = this.completedTutorialVersion;

      return ImfFileIO.writeJsonAtomic(configPath, GSON, data, logger, "tutorial state");
    } catch (RuntimeException e) {
      logger.warn("Failed to save tutorial state", e);
      return false;
    }
  }

  private static class TutorialData {
    boolean completed;
    String completedVersion;
    Integer completedTutorialVersion;
  }
}
