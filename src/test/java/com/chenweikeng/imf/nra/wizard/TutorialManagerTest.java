package com.chenweikeng.imf.nra.wizard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class TutorialManagerTest {
  @TempDir Path tempDir;

  @Test
  void legacyCompletionSurvivesModUpgradeAndRestoresFinishedState() throws Exception {
    Path path = tempDir.resolve("nra-tutorial.json");
    Files.writeString(path, "{\"completed\":true,\"completedVersion\":\"2.4.5\"}");

    TutorialManager migrated = manager(path, "3.3.0");

    assertTrue(migrated.isCompletedForCurrentTutorial());
    assertEquals(TutorialState.FINISHED, migrated.getState());
    assertFalse(migrated.shouldStartTutorial());
    String migratedJson = Files.readString(path);
    assertTrue(migratedJson.contains("\"completedTutorialVersion\": 1"));
    assertTrue(migratedJson.contains("\"completedVersion\": \"2.4.5\""));

    TutorialManager afterUpgrade = manager(path, "3.4.0");

    assertTrue(afterUpgrade.isCompletedForCurrentTutorial());
    assertEquals(TutorialState.FINISHED, afterUpgrade.getState());
    assertFalse(afterUpgrade.shouldStartTutorial());
  }

  @Test
  void legacyUnknownVersionIsRepairedDuringMigration() throws Exception {
    Path path = tempDir.resolve("nra-tutorial.json");
    Files.writeString(path, "{\"completed\":true,\"completedVersion\":\"unknown\"}");

    TutorialManager migrated = manager(path, "3.3.0");

    assertTrue(migrated.isCompletedForCurrentTutorial());
    String migratedJson = Files.readString(path);
    assertTrue(migratedJson.contains("\"completedTutorialVersion\": 1"));
    assertTrue(migratedJson.contains("\"completedVersion\": \"3.3.0\""));
  }

  @Test
  void finishPersistsTutorialVersionAndDoesNotRepeatAfterRestart() throws Exception {
    Path path = tempDir.resolve("nra-tutorial.json");
    TutorialManager firstRun = manager(path, "3.3.0");

    assertTrue(firstRun.shouldStartTutorial());
    firstRun.finishTutorial();

    assertTrue(firstRun.isCompletedForCurrentTutorial());
    assertFalse(firstRun.shouldStartTutorial());
    TutorialManager afterRestart = manager(path, "3.3.0");
    assertEquals(TutorialState.FINISHED, afterRestart.getState());
    assertFalse(afterRestart.shouldStartTutorial());
    String savedJson = Files.readString(path);
    assertTrue(savedJson.contains("\"completedTutorialVersion\": 1"));
    assertTrue(savedJson.contains("\"completedVersion\": \"3.3.0\""));
  }

  @Test
  void incompleteTutorialStillStartsOnImagineFun() {
    TutorialManager manager = manager(tempDir.resolve("nra-tutorial.json"), "3.3.0");

    assertFalse(manager.isCompletedForCurrentTutorial());
    assertEquals(TutorialState.NOT_STARTED, manager.getState());
    assertTrue(manager.shouldStartTutorial());
  }

  private static TutorialManager manager(Path path, String modVersion) {
    return new TutorialManager(
        path, () -> modVersion, () -> true, LoggerFactory.getLogger("TutorialManagerTest"));
  }
}
