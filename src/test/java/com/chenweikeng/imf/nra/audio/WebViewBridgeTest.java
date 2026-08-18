package com.chenweikeng.imf.nra.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class WebViewBridgeTest {
  @Test
  void coldWebKitStartupAllowsMoreThanOldFifteenSecondBoundary() {
    assertTrue(WebViewBridge.HELPER_READY_TIMEOUT_SECONDS >= 30);
  }

  @Test
  void gpuProcessChangeTriggersCompleteEngineFailureRecoverySignal() {
    WebViewBridge bridge = new WebViewBridge();
    AtomicReference<String> failure = new AtomicReference<>();
    bridge.setEngineFailureListener(failure::set);

    bridge.handleResponse(
        new JSONObject().put("type", "gpu_process_changed").put("oldPid", 100).put("newPid", 101));

    assertEquals("gpu-process-changed", failure.get());
  }

  @Test
  void webContentTerminationTriggersCompleteEngineFailureRecoverySignal() {
    WebViewBridge bridge = new WebViewBridge();
    AtomicReference<String> failure = new AtomicReference<>();
    bridge.setEngineFailureListener(failure::set);

    bridge.handleResponse(new JSONObject().put("type", "web_content_terminated"));

    assertEquals("web-content-terminated", failure.get());
  }

  @Test
  void observingStableGpuDoesNotTriggerRecovery() {
    WebViewBridge bridge = new WebViewBridge();
    AtomicReference<String> failure = new AtomicReference<>();
    bridge.setEngineFailureListener(failure::set);

    bridge.handleResponse(new JSONObject().put("type", "gpu_process_observed").put("pid", 100));

    assertNull(failure.get());
  }

  @Test
  void healthyGpuMemoryTelemetryDoesNotTriggerRecovery() {
    WebViewBridge bridge = new WebViewBridge();
    AtomicReference<String> failure = new AtomicReference<>();
    bridge.setEngineFailureListener(failure::set);

    bridge.handleResponse(
        new JSONObject()
            .put("type", "gpu_memory_health")
            .put("pid", 100)
            .put("physicalFootprintBytes", 256L * 1024 * 1024)
            .put("absoluteLimitBytes", 1536L * 1024 * 1024));

    assertNull(failure.get());
  }

  @Test
  void unavailableGpuMemoryTelemetryDoesNotTriggerRecovery() {
    WebViewBridge bridge = new WebViewBridge();
    AtomicReference<String> failure = new AtomicReference<>();
    bridge.setEngineFailureListener(failure::set);

    bridge.handleResponse(
        new JSONObject()
            .put("type", "gpu_memory_unavailable")
            .put("pid", 100)
            .put("consecutiveFailures", 3));

    assertNull(failure.get());
  }

  @Test
  void prolongedGpuMemoryMonitorFailureTriggersCompleteEngineRecoverySignal() {
    WebViewBridge bridge = new WebViewBridge();
    AtomicReference<String> failure = new AtomicReference<>();
    bridge.setEngineFailureListener(failure::set);

    bridge.handleResponse(
        new JSONObject()
            .put("type", "gpu_memory_monitor_failed")
            .put("pid", 100)
            .put("consecutiveFailures", 150)
            .put("unavailableDurationMs", 300_000)
            .put("recycleAfterMs", 300_000));

    assertEquals("gpu-memory-monitor-unavailable", failure.get());
  }

  @Test
  void gpuMemoryPressureTriggersCompleteEngineFailureRecoverySignal() {
    WebViewBridge bridge = new WebViewBridge();
    AtomicReference<String> failure = new AtomicReference<>();
    bridge.setEngineFailureListener(failure::set);

    bridge.handleResponse(
        new JSONObject()
            .put("type", "gpu_memory_pressure")
            .put("reason", "rapid-growth")
            .put("pid", 100)
            .put("physicalFootprintBytes", 900L * 1024 * 1024));

    assertEquals("gpu-memory-pressure-rapid-growth", failure.get());
  }
}
