package com.chenweikeng.imf.nra.spacemountain;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.chenweikeng.imf.nra.ride.RideName;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Captures server-sent block updates during a fixed window of a Hyperspace/Space Mountain ride
 * (default 40-55 s elapsed) into a CSV file under {@code debug-dumps/}. Used to study what
 * animations the server emits in specific show beats so we can replicate the effect across other
 * surfaces.
 *
 * <p>CSV columns: {@code timestamp_ms, elapsed_s, x, y, z, state, source}. Source is one of {@code
 * "block"} (single-block update packet) or {@code "section"} (multi-block section update packet).
 * State is the block-state string (e.g. {@code "minecraft:redstone_lamp[lit=true]"}).
 *
 * <p>Lifecycle is identical to {@link com.chenweikeng.imf.nra.spacemountain
 * .SpaceMountainBlockOverride}'s gate logic — the recorder opens a file on the first tick where the
 * recording window is active and closes it on the first tick where it's not. One file per ride
 * session within the window.
 */
public final class SpaceMountainAnimationRecorder {
  private static final String OUT_DIR = "/Users/cusgadmin/if-local/imf/debug-dumps";
  private static final int WINDOW_START_SECONDS = 40;
  private static final int WINDOW_END_SECONDS = 55;

  private static BufferedWriter writer;
  private static Path currentPath;
  private static int rowsWritten;
  private static boolean previousInWindow;

  private SpaceMountainAnimationRecorder() {}

  public static void register() {
    ClientTickEvents.END_CLIENT_TICK.register(SpaceMountainAnimationRecorder::onTick);
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> closeWriter("disconnect"));
  }

  /** True when we're inside the recording window of an active Hyperspace/Space Mountain ride. */
  public static boolean isRecording() {
    return writer != null;
  }

  private static boolean inWindow() {
    if (!SpaceMountainOverride.isActive()) return false;
    Integer elapsed = CurrentRideHolder.getElapsedSeconds();
    return elapsed != null && elapsed >= WINDOW_START_SECONDS && elapsed <= WINDOW_END_SECONDS;
  }

  private static void onTick(Minecraft mc) {
    if (mc.player == null || mc.level == null) return;
    boolean now = inWindow();
    if (now && !previousInWindow) {
      openWriter();
    } else if (!now && previousInWindow) {
      closeWriter("window-end");
    }
    previousInWindow = now;
  }

  /** Forwarded from the {@code handleBlockUpdate} mixin. */
  public static void recordBlockUpdate(BlockPos pos, BlockState state) {
    if (writer == null) return;
    write(pos, state, "block");
  }

  /** Forwarded from the {@code handleChunkBlocksUpdate} mixin (per-cell within the section). */
  public static void recordSectionUpdate(BlockPos pos, BlockState state) {
    if (writer == null) return;
    write(pos, state, "section");
  }

  private static void write(BlockPos pos, BlockState state, String source) {
    try {
      Integer elapsed = CurrentRideHolder.getElapsedSeconds();
      String stateStr = serializeState(state);
      // Quote the state field — it can contain commas inside [k=v,k=v] property lists.
      writer.write(
          String.format(
              Locale.ROOT,
              "%d,%d,%d,%d,%d,\"%s\",%s%n",
              System.currentTimeMillis(),
              elapsed == null ? -1 : elapsed,
              pos.getX(),
              pos.getY(),
              pos.getZ(),
              stateStr.replace("\"", "\"\""),
              source));
      rowsWritten++;
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.error("[SpaceMountainAnimationRecorder] write failed", e);
      closeWriter("write-error");
    }
  }

  /** Serialize a BlockState as {@code namespace:id[k=v,k2=v2]} with no spaces. */
  private static String serializeState(BlockState state) {
    StringBuilder sb = new StringBuilder();
    sb.append(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    if (!state.getProperties().isEmpty()) {
      sb.append('[');
      boolean first = true;
      for (Property<?> prop : state.getProperties()) {
        if (!first) sb.append(',');
        sb.append(prop.getName()).append('=').append(getPropertyValueName(state, prop));
        first = false;
      }
      sb.append(']');
    }
    return sb.toString();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static String getPropertyValueName(BlockState state, Property prop) {
    return prop.getName(state.getValue(prop));
  }

  private static void openWriter() {
    closeWriter("re-open"); // defensive
    try {
      Path dir = Path.of(OUT_DIR);
      Files.createDirectories(dir);
      RideName ride = CurrentRideHolder.getCurrentRide();
      String shortName = ride != null ? ride.getShortName() : "unknown";
      long now = System.currentTimeMillis();
      currentPath = dir.resolve("animation-" + shortName + "-" + now + ".csv");
      writer =
          Files.newBufferedWriter(
              currentPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      writer.write(
          "# IMF animation recorder v1 ride="
              + (ride != null ? ride.toMatchString() : "unknown")
              + " window="
              + WINDOW_START_SECONDS
              + "-"
              + WINDOW_END_SECONDS
              + "s start_ms="
              + now
              + System.lineSeparator());
      writer.write("timestamp_ms,elapsed_s,x,y,z,state,source" + System.lineSeparator());
      rowsWritten = 0;
      NotRidingAlertClient.LOGGER.info(
          "[SpaceMountainAnimationRecorder] recording → {}", currentPath);
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.error("[SpaceMountainAnimationRecorder] open failed", e);
      writer = null;
      currentPath = null;
    }
  }

  private static void closeWriter(String reason) {
    if (writer == null) return;
    try {
      writer.flush();
      writer.close();
      NotRidingAlertClient.LOGGER.info(
          "[SpaceMountainAnimationRecorder] closed ({}, {} rows) → {}",
          reason,
          rowsWritten,
          currentPath);
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.error("[SpaceMountainAnimationRecorder] close failed", e);
    }
    writer = null;
    currentPath = null;
    rowsWritten = 0;
  }
}
