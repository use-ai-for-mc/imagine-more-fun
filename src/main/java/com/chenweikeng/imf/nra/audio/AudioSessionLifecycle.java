package com.chenweikeng.imf.nra.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns every resource associated with one OpenAudioMC session. */
final class AudioSessionLifecycle {
  static final int MAX_MONITOR_FAILURES = 3;
  static final long MONITOR_RETRY_BASE_MS = 3_000;

  private static final Logger LOGGER = LoggerFactory.getLogger("AudioSessionLifecycle");

  record MonitorFailureDecision(boolean retry, long delayMs, int attempt) {}

  private String sessionId;
  private AutoCloseable helper;
  private ScheduledFuture<?> monitorTask;
  private final List<ScheduledFuture<?>> relatedTasks = new ArrayList<>();
  private int consecutiveMonitorFailures;

  synchronized void start(String newSessionId, AutoCloseable newHelper) {
    stop("replaced-by-new-session");
    sessionId = newSessionId;
    helper = newHelper;
    consecutiveMonitorFailures = 0;
    LOGGER.info("Audio session created (session={})", sessionId);
  }

  synchronized String sessionId() {
    return sessionId;
  }

  synchronized boolean isActive() {
    return sessionId != null;
  }

  synchronized void setMonitorTask(ScheduledFuture<?> task) {
    if (monitorTask != null) {
      monitorTask.cancel(false);
    }
    monitorTask = task;
  }

  synchronized void trackTask(ScheduledFuture<?> task) {
    if (task != null) {
      relatedTasks.add(task);
    }
  }

  synchronized void monitorSucceeded() {
    consecutiveMonitorFailures = 0;
  }

  synchronized MonitorFailureDecision monitorFailed(Throwable failure) {
    consecutiveMonitorFailures++;
    int attempt = consecutiveMonitorFailures;
    if (attempt >= MAX_MONITOR_FAILURES) {
      LOGGER.error(
          "Monitor failed repeatedly; stopping session (session={}, attempts={}, cause={})",
          sessionId,
          attempt,
          failure.toString());
      stop("monitor-failure-limit");
      return new MonitorFailureDecision(false, 0, attempt);
    }
    long delay = MONITOR_RETRY_BASE_MS << (attempt - 1);
    LOGGER.warn(
        "Monitor failed; retrying with backoff (session={}, attempt={}/{}, delayMs={}, cause={})",
        sessionId,
        attempt,
        MAX_MONITOR_FAILURES,
        delay,
        failure.toString());
    return new MonitorFailureDecision(true, delay, attempt);
  }

  synchronized void serverEnded() {
    stop("server-ended");
  }

  synchronized void helperDisconnected() {
    stop("helper-channel-disconnected");
  }

  synchronized void leaveServer() {
    stop("left-server");
  }

  synchronized void minecraftStopping() {
    stop("minecraft-stopping");
  }

  synchronized void stop(String reason) {
    if (sessionId == null && helper == null && monitorTask == null && relatedTasks.isEmpty()) {
      return;
    }

    String stoppedSessionId = sessionId;
    LOGGER.info("Stopping audio session (session={}, reason={})", stoppedSessionId, reason);
    if (monitorTask != null) {
      monitorTask.cancel(false);
      monitorTask = null;
      LOGGER.info("Audio monitor stopped (session={})", stoppedSessionId);
    }
    for (ScheduledFuture<?> task : relatedTasks) {
      task.cancel(false);
    }
    relatedTasks.clear();

    AutoCloseable stoppedHelper = helper;
    helper = null;
    sessionId = null;
    consecutiveMonitorFailures = 0;
    if (stoppedHelper != null) {
      try {
        stoppedHelper.close();
      } catch (Exception e) {
        LOGGER.warn("Failed to close audio helper (session={})", stoppedSessionId, e);
      }
      LOGGER.info("WebView destroyed (session={})", stoppedSessionId);
    }
    LOGGER.info("Audio session stopped (session={}, reason={})", stoppedSessionId, reason);
  }
}
