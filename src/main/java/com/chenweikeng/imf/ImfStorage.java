package com.chenweikeng.imf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Central registry of filesystem paths for ImagineMoreFun's on-disk state.
 *
 * <p>Everything NRA-related now lives under {@code <configDir>/imaginemorefun/} instead of being
 * scattered at the root of {@code config/}. Call sites should pull their paths from here rather
 * than hard-coding.
 *
 * <p>SkinCache continues to use {@code <gameDir>/skincache/} (separate tree under the game
 * directory, not the config directory) and is not routed through this class. PIM has no disk state.
 */
public final class ImfStorage {

  private ImfStorage() {}

  /** Subdirectory name under {@code configDir}. Also used as the {@code fabric.mod.json} id. */
  public static final String ROOT_NAME = "imaginemorefun";

  public static Path root() {
    Path p = FabricLoader.getInstance().getConfigDir().resolve(ROOT_NAME);
    try {
      Files.createDirectories(p);
    } catch (IOException e) {
      // Swallow — caller will see the error when it tries to write.
    }
    return p;
  }

  // NRA-owned files. Each was previously at config/not-riding-alert*.json.

  public static Path nraConfig() {
    return root().resolve("nra-config.json");
  }

  public static Path nraRides() {
    return root().resolve("nra-rides.json");
  }

  public static Path nraRideSnapshots() {
    return root().resolve("nra-ride-snapshots.json");
  }

  public static Path nraSession() {
    return root().resolve("nra-session.json");
  }

  public static Path nraTutorial() {
    return root().resolve("nra-tutorial.json");
  }

  public static Path nraProfiles() {
    return root().resolve("nra-profiles.json");
  }

  public static Path nraHistory() {
    return root().resolve("nra-history.json");
  }

  public static Path nraDailyPlan() {
    return root().resolve("nra-daily-plan.json");
  }

  public static Path nraDailyQuests() {
    return root().resolve("nra-daily-quests.json");
  }

  /** Directory for user-overridden WebView helper binaries (OpenAudioMc integration). */
  public static Path nativeHelperDir() {
    return root().resolve("native");
  }

  /**
   * Marker file written once migration from the old {@code config/not-riding-alert*} paths has run.
   */
  public static Path migrationMarker() {
    return root().resolve(".migrated-v1");
  }

  /** Marker file written once the first-launch Ride Plan nudge has been applied. */
  public static Path ridePlanNudgeMarker() {
    return root().resolve(".ride-plan-nudge");
  }
}
