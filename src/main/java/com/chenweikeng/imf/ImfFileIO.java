package com.chenweikeng.imf;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;

/** Small, defensive file helpers for user-writable IMF state. */
public final class ImfFileIO {
  private static final DateTimeFormatter CORRUPT_STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  private ImfFileIO() {}

  public static <T> T readJson(
      Path path, Gson gson, Class<T> type, Logger logger, String description) {
    return readJson(path, gson, (Type) type, logger, description);
  }

  @SuppressWarnings("unchecked")
  public static <T> T readJson(Path path, Gson gson, Type type, Logger logger, String description) {
    if (!Files.exists(path)) {
      return null;
    }

    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      T value = (T) gson.fromJson(reader, type);
      if (value == null) {
        quarantineCorruptFile(path, logger, description, new JsonParseException("empty JSON"));
      }
      return value;
    } catch (JsonParseException | IllegalStateException | NumberFormatException e) {
      quarantineCorruptFile(path, logger, description, e);
    } catch (IOException e) {
      logger.warn("Failed to read {} from {}", description, path, e);
    } catch (RuntimeException e) {
      quarantineCorruptFile(path, logger, description, e);
    }
    return null;
  }

  public static boolean writeJsonAtomic(
      Path path, Gson gson, Object value, Logger logger, String description) {
    return writeAtomic(
        path,
        writer -> gson.toJson(value, writer),
        logger,
        "Failed to save " + description + " to " + path);
  }

  public static boolean writeStringAtomic(
      Path path, String value, Logger logger, String description) {
    return writeAtomic(
        path,
        writer -> writer.write(value),
        logger,
        "Failed to save " + description + " to " + path);
  }

  public static Path quarantineCorruptFile(
      Path path, Logger logger, String description, RuntimeException cause) {
    Path backup = corruptBackupPath(path);
    try {
      moveReplacing(path, backup);
      logger.warn(
          "Could not parse {}; moved corrupt file {} to {}", description, path, backup, cause);
      return backup;
    } catch (IOException moveError) {
      logger.warn(
          "Could not parse {}; also failed to move corrupt file {} to {}",
          description,
          path,
          backup,
          moveError);
      logger.warn("Original parse failure for {} at {}", description, path, cause);
      return null;
    }
  }

  private static boolean writeAtomic(
      Path path, ThrowingWriter writerAction, Logger logger, String errorMessage) {
    Path temp = tempPath(path);
    try {
      Path parent = path.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      try (Writer writer =
          Files.newBufferedWriter(
              temp,
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE)) {
        writerAction.write(writer);
      }
      moveReplacing(temp, path);
      return true;
    } catch (IOException | RuntimeException e) {
      logger.error(errorMessage, e);
      try {
        Files.deleteIfExists(temp);
      } catch (IOException ignored) {
      }
      return false;
    }
  }

  private static void moveReplacing(Path source, Path target) throws IOException {
    try {
      Files.move(
          source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static Path tempPath(Path path) {
    String name = path.getFileName().toString();
    String tempName =
        "." + name + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime();
    Path parent = path.getParent();
    return parent == null ? Path.of(tempName) : parent.resolve(tempName);
  }

  private static Path corruptBackupPath(Path path) {
    String name = path.getFileName().toString();
    String stamp = LocalDateTime.now().format(CORRUPT_STAMP);
    Path parent = path.getParent();
    Path dir = parent == null ? Path.of(".") : parent;
    Path candidate = dir.resolve(name + ".corrupt-" + stamp + ".bak");
    int suffix = 2;
    while (Files.exists(candidate)) {
      candidate = dir.resolve(name + ".corrupt-" + stamp + "-" + suffix + ".bak");
      suffix++;
    }
    return candidate;
  }

  @FunctionalInterface
  private interface ThrowingWriter {
    void write(Writer writer) throws IOException;
  }
}
