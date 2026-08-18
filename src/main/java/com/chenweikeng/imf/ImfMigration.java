package com.chenweikeng.imf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time migration of NRA on-disk state into {@code config/imaginemorefun/}.
 *
 * <p>On first launch of the merged mod, each legacy path under {@code config/} is moved into the
 * new location. After a successful pass, a marker file is written and subsequent launches
 * short-circuit.
 *
 * <p>SkinCache's {@code <gameDir>/skincache/} tree is intentionally left alone — per user
 * direction, skincache storage is not migrated.
 */
public final class ImfMigration {

  private static final Logger LOGGER = LoggerFactory.getLogger("imaginemorefun/migration");

  private ImfMigration() {}

  public static void runOnce() {
    Path configDir = FabricLoader.getInstance().getConfigDir();
    Path marker = ImfStorage.migrationMarker();
    if (Files.exists(marker)) {
      return;
    }

    // Ensure the destination exists before moving anything.
    ImfStorage.root();

    // File moves: (old absolute path, new absolute path).
    moveIfExists(configDir.resolve("not-riding-alert.json"), ImfStorage.nraConfig());
    moveIfExists(configDir.resolve("not-riding-alert-rides.json"), ImfStorage.nraRides());
    moveIfExists(
        configDir.resolve("not-riding-alert-ride-snapshots.json"), ImfStorage.nraRideSnapshots());
    moveIfExists(configDir.resolve("not-riding-alert-session.json"), ImfStorage.nraSession());
    moveIfExists(configDir.resolve("not-riding-alert-tutorial.json"), ImfStorage.nraTutorial());
    moveIfExists(configDir.resolve("not-riding-alert-profiles.json"), ImfStorage.nraProfiles());
    moveIfExists(configDir.resolve("not-riding-alert-history.json"), ImfStorage.nraHistory());

    // Directory move: the native helper override directory.
    Path oldNativeDir = configDir.resolve("not-riding-alert").resolve("native");
    Path newNativeDir = ImfStorage.nativeHelperDir();
    moveDirIfExists(oldNativeDir, newNativeDir);

    // If the old wrapper dir is now empty, clean it up.
    Path oldWrapperDir = configDir.resolve("not-riding-alert");
    if (Files.isDirectory(oldWrapperDir)) {
      try (var stream = Files.list(oldWrapperDir)) {
        if (stream.findAny().isEmpty()) {
          Files.delete(oldWrapperDir);
        }
      } catch (IOException e) {
        LOGGER.warn("Failed to clean up empty {}: {}", oldWrapperDir, e.getMessage());
      }
    }

    try {
      Files.writeString(marker, "migrated to imaginemorefun v1\n");
    } catch (IOException e) {
      LOGGER.warn("Failed to write migration marker {}: {}", marker, e.getMessage());
    }
  }

  private static void moveIfExists(Path oldPath, Path newPath) {
    if (!Files.exists(oldPath)) {
      return;
    }
    if (Files.exists(newPath)) {
      LOGGER.warn(
          "Both old path {} and new path {} exist; leaving old path alone", oldPath, newPath);
      return;
    }
    try {
      Files.move(oldPath, newPath, StandardCopyOption.ATOMIC_MOVE);
      LOGGER.info("Migrated {} -> {}", oldPath, newPath);
    } catch (IOException e) {
      try {
        Files.move(oldPath, newPath); // fall back to non-atomic on cross-fs moves
        LOGGER.info("Migrated {} -> {} (non-atomic)", oldPath, newPath);
      } catch (IOException ee) {
        LOGGER.error("Failed to migrate {} -> {}: {}", oldPath, newPath, ee.getMessage());
      }
    }
  }

  private static void moveDirIfExists(Path oldDir, Path newDir) {
    if (!Files.isDirectory(oldDir)) {
      return;
    }
    if (Files.exists(newDir)) {
      LOGGER.warn("Both old dir {} and new dir {} exist; leaving old dir alone", oldDir, newDir);
      return;
    }
    try {
      Files.move(oldDir, newDir);
      LOGGER.info("Migrated directory {} -> {}", oldDir, newDir);
    } catch (IOException e) {
      LOGGER.error("Failed to migrate directory {} -> {}: {}", oldDir, newDir, e.getMessage());
    }
  }
}
