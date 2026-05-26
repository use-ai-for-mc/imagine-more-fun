package com.chenweikeng.imf.nra.status;

import com.chenweikeng.imf.nra.ride.AutograbHolder;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.chenweikeng.imf.nra.ride.RideName;
import com.chenweikeng.imf.nra.util.TimeFormatUtil;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the native status helper (macOS menu bar / Windows tray) with the remaining ride time.
 * Runs unconditionally whenever the player is detected to be on a ride — independent of the
 * cursor-release / auto-minimize feature. Detection mirrors {@code
 * StrategyHudRendererDispatcher.render}: any of the in-ride scoreboard entry, or the autograb
 * region, counts as riding.
 */
public final class StatusBarController {
  private static final Logger LOGGER = LoggerFactory.getLogger("StatusBarController");
  private static final StatusBarController INSTANCE = new StatusBarController();
  private static final String NO_TIMING_PLACEHOLDER = "--:--";

  public static StatusBarController getInstance() {
    return INSTANCE;
  }

  private volatile StatusBarBridge bridge;
  private final AtomicBoolean starting = new AtomicBoolean(false);
  private volatile boolean shutdownHookRegistered;
  private volatile boolean disabledForSession;
  private String lastTextSent = "";

  private StatusBarController() {}

  public void tick(Minecraft client) {
    RideName currentRide = CurrentRideHolder.getCurrentRide();
    RideName autograbRide = client != null ? AutograbHolder.getRideAtLocation(client) : null;
    boolean isRiding = currentRide != null || autograbRide != null;

    if (!isRiding) {
      if (!lastTextSent.isEmpty()) {
        sendIfRunning("");
        lastTextSent = "";
      }
      return;
    }

    ensureStarted();

    String text = computeText(currentRide);
    if (!text.equals(lastTextSent)) {
      sendIfRunning(text);
      lastTextSent = text;
    }
  }

  public void onDisconnect() {
    lastTextSent = "";
    StatusBarBridge b = bridge;
    if (b != null && b.isRunning()) {
      b.setText("");
    }
  }

  public void shutdown() {
    lastTextSent = "";
    StatusBarBridge b = bridge;
    bridge = null;
    if (b != null) {
      b.stop();
    }
  }

  private String computeText(RideName currentRide) {
    if (currentRide == null) {
      return NO_TIMING_PLACEHOLDER;
    }
    if (currentRide == RideName.DAVY_CROCKETTS_EXPLORER_CANOES) {
      // Canoe has no fixed duration; report position-based progress published by
      // CanoeHelperClient instead.
      Integer percent = CurrentRideHolder.getCurrentProgressPercent();
      return percent == null ? NO_TIMING_PLACEHOLDER : percent + "%";
    }
    Integer elapsed = CurrentRideHolder.getElapsedSeconds();
    if (elapsed == null) {
      return NO_TIMING_PLACEHOLDER;
    }
    int remaining = Math.max(0, currentRide.getRideTime() - elapsed);
    return TimeFormatUtil.formatDuration(remaining);
  }

  private void sendIfRunning(String text) {
    StatusBarBridge b = bridge;
    if (b != null && b.isRunning()) {
      b.setText(text);
    }
  }

  private void ensureStarted() {
    // A prior launch attempt failed (most often: no compatible .NET runtime). Don't retry on
    // every ride tick — that's what produced a relaunch (and, before the popup fix, a dialog)
    // roughly every READY_TIMEOUT_SECONDS. Stays disabled until the game restarts.
    if (disabledForSession) {
      return;
    }
    StatusBarBridge existing = bridge;
    if (existing != null && existing.isRunning()) {
      return;
    }
    if (!starting.compareAndSet(false, true)) {
      return;
    }
    Thread t =
        new Thread(
            () -> {
              try {
                StatusBarBridge b = new StatusBarBridge();
                if (b.start()) {
                  bridge = b;
                  registerShutdownHookOnce();
                } else {
                  disabledForSession = true;
                  LOGGER.info(
                      "Status helper unavailable; tray countdown disabled for this session");
                }
              } catch (RuntimeException e) {
                disabledForSession = true;
                LOGGER.warn(
                    "Unexpected error starting status helper; tray countdown disabled for this"
                        + " session",
                    e);
              } finally {
                starting.set(false);
              }
            },
            "StatusBarController-Starter");
    t.setDaemon(true);
    t.start();
  }

  private void registerShutdownHookOnce() {
    if (shutdownHookRegistered) {
      return;
    }
    shutdownHookRegistered = true;
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  StatusBarBridge b = bridge;
                  if (b != null) {
                    b.stop();
                  }
                },
                "StatusBarController-Shutdown"));
  }
}
