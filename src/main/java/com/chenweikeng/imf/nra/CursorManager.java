package com.chenweikeng.imf.nra;

import com.chenweikeng.imf.nra.canoe.CanoeHelperClient;
import com.chenweikeng.imf.nra.compat.MonkeycraftCompat;
import com.chenweikeng.imf.nra.config.CursorReleaseTiming;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.config.WindowMinimizeTiming;
import com.chenweikeng.imf.nra.handler.WindowMinimizeHandler;
import com.chenweikeng.imf.nra.ride.AutograbHolder;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.chenweikeng.imf.nra.ride.RideName;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class CursorManager {
  public static final Component DYNAMIC_FPS_COMPATIBILITY_MESSAGE =
      Component.literal(
          "§6✨ §e[IMF] §fFor compatibility with Dynamic FPS, the window will not be minimized when MonkeyCraft client is connected.");

  private boolean wasRiding = false;
  private boolean wasOnVehicle = false;
  private boolean wasPassenger = false;
  private boolean wasReadyToMinimize = false;
  private boolean minimizedDuringAutograb = false;
  private boolean autograbFailureRestored = false;
  private long pendingZoneMinimizeTick = -1;
  private RideName previousAutograbRide = null;
  private long lastCanoeMessageTick = -Timing.CANOE_MESSAGE_COOLDOWN_TICKS;
  private long lastDynamicFpsMessageTick = -Timing.DYNAMIC_FPS_MESSAGE_COOLDOWN_TICKS;
  private final WindowMinimizeHandler windowMinimizeHandler = WindowMinimizeHandler.getInstance();

  /**
   * Main per-tick entry point. Delegates to {@link #tickCursorRelease} and {@link
   * #tickWindowMinimize}, then snapshots state for the next tick.
   */
  public void tick(Minecraft client, boolean isPassenger, boolean isRiding, RideName autograbRide) {
    windowMinimizeHandler.tickMonitor();

    tickCursorRelease(client, isPassenger, isRiding, autograbRide);

    boolean isOnVehicle = isPassenger || CurrentRideHolder.getCurrentRide() != null;
    tickWindowMinimize(client, isPassenger, isRiding, isOnVehicle, autograbRide);

    // Snapshot state for next tick's edge detection.
    wasRiding = isRiding;
    wasOnVehicle = isOnVehicle;
    wasPassenger = isPassenger;
    wasReadyToMinimize = isOnVehicle && isReadyToMinimizeForCurrentRide();
  }

  // ---------------------------------------------------------------------------
  // Cursor release / grab
  // ---------------------------------------------------------------------------

  private void tickCursorRelease(
      Minecraft client, boolean isPassenger, boolean isRiding, RideName autograbRide) {
    GameState state = GameState.getInstance();
    CursorReleaseTiming timing = ModConfig.currentSetting.cursorReleaseTiming;

    if (timing == CursorReleaseTiming.ON_ZONE_ENTRY && autograbRide != null && !isPassenger) {
      if (autograbRide != previousAutograbRide) {
        client.setScreen(null);
        if (client.mouseHandler.isMouseGrabbed()) {
          client.mouseHandler.releaseMouse();
          state.setAutomaticallyReleasedCursor(true);
          sendCanoeMessageIfNeeded(client, autograbRide);
        }
        previousAutograbRide = autograbRide;
      }
    } else {
      previousAutograbRide = null;
    }

    if (timing == CursorReleaseTiming.NONE) {
      return;
    }

    boolean isOnVehicle = isPassenger || CurrentRideHolder.getCurrentRide() != null;

    boolean shouldReleaseOnThisTick =
        switch (timing) {
          case NONE -> false;
          case ON_ZONE_ENTRY -> !wasRiding && isRiding;
          case ON_VEHICLE_MOUNT -> !wasOnVehicle && isOnVehicle;
        };

    if (shouldReleaseOnThisTick) {
      client.mouseHandler.releaseMouse();
      state.setAutomaticallyReleasedCursor(true);
      RideName currentRide = CurrentRideHolder.getCurrentRide();
      if (currentRide == null) {
        currentRide = AutograbHolder.getRideAtLocation(client);
      }
      sendCanoeMessageIfNeeded(client, currentRide);
    }

    boolean shouldGrabOnThisTick =
        switch (timing) {
          case NONE -> false;
          case ON_ZONE_ENTRY -> wasRiding && !isRiding;
          case ON_VEHICLE_MOUNT -> wasOnVehicle && !isOnVehicle;
        };

    if (shouldGrabOnThisTick) {
      state.setAutomaticallyReleasedCursor(false);
      if (client.screen == null) {
        client.mouseHandler.grabMouse();
      }
    }

    boolean isCurrentlyRiding =
        switch (timing) {
          case NONE -> false;
          case ON_ZONE_ENTRY -> isRiding;
          case ON_VEHICLE_MOUNT -> isOnVehicle;
        };

    if ((isCurrentlyRiding || client.player.isPassenger())
        && client.mouseHandler.isRightPressed()
        && client.screen == null) {
      client.mouseHandler.releaseMouse();
    }
  }

  // ---------------------------------------------------------------------------
  // Window minimize
  // ---------------------------------------------------------------------------

  private void tickWindowMinimize(
      Minecraft client,
      boolean isPassenger,
      boolean isRiding,
      boolean isOnVehicle,
      RideName autograbRide) {
    WindowMinimizeTiming minimizeTiming = ModConfig.currentSetting.minimizeWindow;
    if (minimizeTiming == WindowMinimizeTiming.NONE) {
      return;
    }

    GameState state = GameState.getInstance();
    long currentTick = state.getAbsoluteTickCounter();

    // Zone-entry minimize: arm a deferred timer on zone entry.
    if (!wasRiding && isRiding && minimizeTiming == WindowMinimizeTiming.ON_ZONE_ENTRY) {
      pendingZoneMinimizeTick = currentTick;
    }
    if (!isRiding) {
      pendingZoneMinimizeTick = -1;
    }

    boolean shouldMinimizeOnZoneEntry =
        pendingZoneMinimizeTick != -1
            && (currentTick - pendingZoneMinimizeTick) >= Timing.ZONE_ENTRY_MINIMIZE_DELAY_TICKS;

    boolean readyToMinimize = isOnVehicle && isReadyToMinimizeForCurrentRide();
    boolean shouldMinimizeOnVehicleMount =
        !wasReadyToMinimize && readyToMinimize && !minimizedDuringAutograb;

    boolean shouldMinimizeOnThisTick =
        switch (minimizeTiming) {
          case NONE -> false;
          case ON_ZONE_ENTRY -> shouldMinimizeOnZoneEntry || shouldMinimizeOnVehicleMount;
          case ON_VEHICLE_MOUNT -> shouldMinimizeOnVehicleMount;
        };

    if (shouldMinimizeOnThisTick) {
      if (MonkeycraftCompat.isClientConnected()
          && FabricLoader.getInstance().isModLoaded("dynamic_fps")) {
        sendDynamicFpsMessageIfNeeded(client);
      } else {
        if (shouldMinimizeOnZoneEntry && minimizeTiming == WindowMinimizeTiming.ON_ZONE_ENTRY) {
          minimizedDuringAutograb = true;
          if (ModConfig.currentSetting.showAutograbRegions
              && pendingZoneMinimizeTick != -1
              && client.player != null) {
            state.armRubberBand(
                client.player.getX(),
                client.player.getY(),
                client.player.getZ(),
                pendingZoneMinimizeTick + 20);
          }
        }
        windowMinimizeHandler.minimizeWindow();
      }
      pendingZoneMinimizeTick = -1;
    }

    tickRubberBand(state, client, isPassenger, autograbRide, currentTick);

    if (!isRiding) {
      minimizedDuringAutograb = false;
    }

    boolean shouldRestoreOnThisTick =
        switch (minimizeTiming) {
          case NONE -> false;
          case ON_ZONE_ENTRY -> wasRiding && !isRiding;
          case ON_VEHICLE_MOUNT -> wasOnVehicle && !isOnVehicle;
        };

    if (shouldRestoreOnThisTick) {
      state.setWindowRestoreGrace(10);
      windowMinimizeHandler.restoreWindow();
    }
    if (wasRiding && !isRiding) {
      windowMinimizeHandler.requestAttention();
    }

    // DynamicFPS + MonkeyCraft compatibility: keep window visible.
    if (MonkeycraftCompat.isClientConnected()
        && FabricLoader.getInstance().isModLoaded("dynamic_fps")) {
      if (client.getWindow() != null) {
        long handle = client.getWindow().handle();
        boolean isMinimized =
            GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE;
        if (isMinimized) {
          windowMinimizeHandler.restoreWindow();
          sendDynamicFpsMessageIfNeeded(client);
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Rubber band
  // ---------------------------------------------------------------------------

  private void tickRubberBand(
      GameState state,
      Minecraft client,
      boolean isPassenger,
      RideName autograbRide,
      long currentTick) {
    if (!state.isRubberBandActive()) {
      return;
    }
    if (isPassenger || currentTick > state.getRubberBandUntilTick() || client.player == null) {
      state.clearRubberBand();
    } else if (autograbRide != null) {
      state.updateRubberBandAnchor(
          client.player.getX(), client.player.getY(), client.player.getZ());
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private boolean isReadyToMinimizeForCurrentRide() {
    RideName currentRide = CurrentRideHolder.getCurrentRide();
    if (currentRide != RideName.DAVY_CROCKETTS_EXPLORER_CANOES) {
      return true;
    }
    return CanoeHelperClient.get().hasCanoeStarted();
  }

  public boolean wasPassenger() {
    return wasPassenger;
  }

  public void clearAutograbFailureRestored() {
    autograbFailureRestored = false;
  }

  public void handleAutograbFailureRestore() {
    if (autograbFailureRestored) {
      return;
    }
    autograbFailureRestored = true;
    GameState.getInstance().clearRubberBand();
    if (ModConfig.currentSetting.minimizeWindow != WindowMinimizeTiming.NONE) {
      windowMinimizeHandler.restoreWindow();
      minimizedDuringAutograb = false;
    }
    windowMinimizeHandler.requestAttention();
  }

  private void sendCanoeMessageIfNeeded(Minecraft client, RideName ride) {
    if (client.player == null || ride != RideName.DAVY_CROCKETTS_EXPLORER_CANOES) {
      return;
    }
    GameState state = GameState.getInstance();
    if (state.getAbsoluteTickCounter() - lastCanoeMessageTick
        < Timing.CANOE_MESSAGE_COOLDOWN_TICKS) {
      return;
    }
    lastCanoeMessageTick = state.getAbsoluteTickCounter();
    Component message =
        Component.literal("§6✨ §e[IMF] §fPlease use §e§lLEFT click§r§f to ride canoes.");
    client.player.displayClientMessage(message, false);
  }

  private void sendDynamicFpsMessageIfNeeded(Minecraft client) {
    if (client.player == null) {
      return;
    }
    GameState state = GameState.getInstance();
    if (state.getAbsoluteTickCounter() - lastDynamicFpsMessageTick
        < Timing.DYNAMIC_FPS_MESSAGE_COOLDOWN_TICKS) {
      return;
    }
    lastDynamicFpsMessageTick = state.getAbsoluteTickCounter();
    client.player.displayClientMessage(DYNAMIC_FPS_COMPATIBILITY_MESSAGE, false);
  }

  public void reset() {
    wasRiding = false;
    wasOnVehicle = false;
    wasPassenger = false;
    wasReadyToMinimize = false;
    minimizedDuringAutograb = false;
    autograbFailureRestored = false;
    pendingZoneMinimizeTick = -1;
    GameState.getInstance().clearRubberBand();
    previousAutograbRide = null;
    lastCanoeMessageTick = -Timing.CANOE_MESSAGE_COOLDOWN_TICKS;
    lastDynamicFpsMessageTick = -Timing.DYNAMIC_FPS_MESSAGE_COOLDOWN_TICKS;
  }
}
