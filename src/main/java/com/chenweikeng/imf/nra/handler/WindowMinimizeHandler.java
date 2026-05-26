package com.chenweikeng.imf.nra.handler;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WindowMinimizeHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger("imaginemorefun/window");
  private static final WindowMinimizeHandler INSTANCE = new WindowMinimizeHandler();
  // Monitor for 15 seconds (300 ticks @ 20tps) after each restore; long enough
  // to catch a click-induced resize/iconify but short enough to avoid log spam.
  private static final int MONITOR_TICKS = 300;
  // If GLFW hands us a frame this small in either axis during the post-restore
  // grace window, assume something (deminiaturize animation frame, window-snap
  // to narrow strip, etc.) bogusly shrank us and force the pre-minimize
  // geometry back. 500 comfortably covers both the ~73x175 dock-thumbnail case
  // and the ~208x1022 narrow-strip snap we've seen in practice.
  private static final int SHRINK_THRESHOLD_PX = 500;

  private int monitorTicksLeft = 0;

  private int savedW = -1;
  private int savedH = -1;
  private int savedX = Integer.MIN_VALUE;
  private int savedY = Integer.MIN_VALUE;
  private boolean savedMaximized = false;
  private boolean hasSavedState = false;
  private boolean recoveryAttempted = false;

  private WindowMinimizeHandler() {}

  public static WindowMinimizeHandler getInstance() {
    return INSTANCE;
  }

  public void tickMonitor() {
    if (monitorTicksLeft <= 0) return;
    Minecraft client = Minecraft.getInstance();
    if (client.getWindow() == null) {
      monitorTicksLeft = 0;
      return;
    }
    long h = client.getWindow().handle();

    int[] sw = new int[1], sh = new int[1];
    int[] px = new int[1], py = new int[1];
    GLFW.glfwGetWindowSize(h, sw, sh);
    GLFW.glfwGetWindowPos(h, px, py);

    if (!recoveryAttempted
        && hasSavedState
        && (sw[0] < SHRINK_THRESHOLD_PX || sh[0] < SHRINK_THRESHOLD_PX)) {
      recoveryAttempted = true;
      LOGGER.warn(
          "[monitor] shrink detected {}x{} at ({},{}); restoring to {}x{} at ({},{}) maximized={}",
          sw[0],
          sh[0],
          px[0],
          py[0],
          savedW,
          savedH,
          savedX,
          savedY,
          savedMaximized);
      int targetW = savedW, targetH = savedH, targetX = savedX, targetY = savedY;
      boolean targetMaximized = savedMaximized;
      client.execute(
          () -> {
            GLFW.glfwSetWindowPos(h, targetX, targetY);
            GLFW.glfwSetWindowSize(h, targetW, targetH);
            if (targetMaximized) {
              GLFW.glfwMaximizeWindow(h);
            }
          });
    }

    monitorTicksLeft--;
  }

  public void minimizeWindow() {
    Minecraft client = Minecraft.getInstance();
    if (client.getWindow() == null) {
      return;
    }

    long handle = client.getWindow().handle();
    boolean isMinimized = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE;

    if (!isMinimized) {
      int[] sw = new int[1], sh = new int[1];
      int[] px = new int[1], py = new int[1];
      GLFW.glfwGetWindowSize(handle, sw, sh);
      GLFW.glfwGetWindowPos(handle, px, py);
      savedW = sw[0];
      savedH = sh[0];
      savedX = px[0];
      savedY = py[0];
      savedMaximized = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE;
      hasSavedState = true;
      client.execute(
          () -> {
            GLFW.glfwIconifyWindow(handle);
          });
    }
  }

  public void requestAttention() {
    Minecraft client = Minecraft.getInstance();
    if (client.getWindow() == null) {
      return;
    }

    long handle = client.getWindow().handle();
    boolean isMinimized = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE;

    if (!isMinimized) {
      client.execute(
          () -> {
            long h = client.getWindow().handle();
            GLFW.glfwFocusWindow(h);
            GLFW.glfwRequestWindowAttention(h);
          });
    }
  }

  public void restoreWindow() {
    Minecraft client = Minecraft.getInstance();
    if (client.getWindow() == null) {
      return;
    }

    long handle = client.getWindow().handle();
    boolean isMinimized = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE;

    if (isMinimized) {
      client.execute(
          () -> {
            GLFW.glfwRestoreWindow(handle);
            GLFW.glfwFocusWindow(handle);
            GLFW.glfwRequestWindowAttention(handle);

            recoveryAttempted = false;
            monitorTicksLeft = MONITOR_TICKS;

            ReminderHandler.getInstance().resetAudioReminderTimer();
          });
    }
  }
}
