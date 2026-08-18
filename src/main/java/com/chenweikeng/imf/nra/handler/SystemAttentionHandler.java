package com.chenweikeng.imf.nra.handler;

import com.chenweikeng.imf.nra.GameState;
import com.chenweikeng.imf.nra.Timing;
import com.chenweikeng.imf.nra.config.ModConfig;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Escalates the not-riding alert to the OS level: bounces the Dock icon on macOS / flashes the
 * taskbar button on Windows via {@code glfwRequestWindowAttention}, so an unfocused or minimized
 * game window still gets the user's attention. GLFW exposes only the one-shot informational
 * request, so sustained intensity is achieved by re-requesting attention on an interval while the
 * alert keeps firing and the window stays unfocused. Unlike {@link WindowMinimizeHandler}, this
 * never steals focus.
 */
public class SystemAttentionHandler {
  private static SystemAttentionHandler instance;

  /** Tick of the last alert seen; staleness beyond one alert cycle ends the escalation. */
  private long lastAlertTick = -1;

  /** Tick of the last OS attention request, for the repeat throttle. */
  private long lastRequestTick = -1;

  private SystemAttentionHandler() {}

  public static SystemAttentionHandler getInstance() {
    if (instance == null) {
      instance = new SystemAttentionHandler();
    }
    return instance;
  }

  /** Called by {@code AlertChecker} whenever the not-riding alert fires. */
  public void onAlert(Minecraft client) {
    if (!ModConfig.currentSetting.systemAttentionOnAlert) {
      return;
    }
    lastAlertTick = GameState.getInstance().getAbsoluteTickCounter();
    requestIfUnfocused(client);
  }

  /** Per-tick escalation: keep re-requesting attention until the user returns to the window. */
  public void tick(Minecraft client) {
    if (lastAlertTick == -1 || !ModConfig.currentSetting.systemAttentionOnAlert) {
      return;
    }
    long currentTick = GameState.getInstance().getAbsoluteTickCounter();
    // The alert stopped firing (riding again, suppression, mod disabled, ...): stand down.
    if (currentTick - lastAlertTick > Timing.ALERT_CHECK_INTERVAL + 40) {
      reset();
      return;
    }
    requestIfUnfocused(client);
  }

  private void requestIfUnfocused(Minecraft client) {
    if (client.getWindow() == null) {
      return;
    }
    long handle = client.getWindow().handle();
    if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE) {
      return;
    }
    long currentTick = GameState.getInstance().getAbsoluteTickCounter();
    if (lastRequestTick != -1
        && currentTick - lastRequestTick < Timing.SYSTEM_ATTENTION_REPEAT_TICKS) {
      return;
    }
    lastRequestTick = currentTick;
    client.execute(() -> GLFW.glfwRequestWindowAttention(handle));
  }

  public void reset() {
    lastAlertTick = -1;
    lastRequestTick = -1;
  }
}
