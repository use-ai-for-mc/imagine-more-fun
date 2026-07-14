package com.chenweikeng.imf.nra.audio;

import com.chenweikeng.imf.nra.handler.ReminderHandler;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages an OpenAudioMC audio session via a headless native webview process. Ports the logic from
 * the MonkeyCraft mobile app's OpenAudioMcService (Flutter).
 *
 * <p>The service:
 *
 * <ol>
 *   <li>Detects OpenAudioMC session URLs in chat messages
 *   <li>Launches a native webview helper process (hidden window with audio)
 *   <li>Polls the DOM every 3 seconds to automate the session
 *   <li>Auto-clicks "Start Audio Session" when the button appears
 *   <li>Detects active audio via presence of a volume slider (input[type="range"])
 *   <li>Reports connection status to {@link ReminderHandler}
 * </ol>
 */
public class OpenAudioMcService {
  private static final Logger LOGGER = LoggerFactory.getLogger("OpenAudioMcService");

  /** Host of OpenAudioMC session URLs; what follows it varies between server versions. */
  private static final String URL_PREFIX = "https://session.openaudiomc.net";

  private static final int MAX_MID_SESSION_DROP_ATTEMPTS = 3;
  private static final int MONITOR_INTERVAL_MS = 3000;
  private static final int CONNECTION_TIMEOUT_MS = 60000;
  // ImagineFun auto-prompts an audio session a few seconds after join (its own /audio).
  // Our fallback request must wait long enough for that server-provided link to arrive and
  // run connect() (which flips isActive), otherwise both fire and the server mints two
  // separate sessions. Observed server latency is ~4-5s; 12s gives a comfortable margin.
  private static final int AUTO_CONNECT_DELAY_MS = 12000;

  /** JavaScript injected every 3 seconds to check DOM state. */
  private static final String STATUS_CHECK_JS =
      """
      (function() {
        var rangeInput = document.querySelector('input[type="range"]');
        var hasRangeInput = !!rangeInput;
        var rangeValue = hasRangeInput ? parseInt(rangeInput.value) : -1;

        var buttons = Array.prototype.slice.call(document.querySelectorAll('button, [role="button"]'));
        var startButton = buttons.find(
          function(el) { return (el.outerText || el.textContent || '').trim().toLowerCase() === 'start audio session'; }
        );
        var hasStartButton = !!startButton;
        if (startButton) {
          var rect = startButton.getBoundingClientRect();
          var common = { bubbles: true, cancelable: true, view: window,
                         clientX: rect.left + rect.width / 2,
                         clientY: rect.top + rect.height / 2,
                         button: 0, buttons: 1 };
          try {
            startButton.dispatchEvent(new PointerEvent('pointerdown', common));
            startButton.dispatchEvent(new PointerEvent('pointerup', common));
          } catch(pe) {}
          startButton.dispatchEvent(new MouseEvent('mousedown', common));
          startButton.dispatchEvent(new MouseEvent('mouseup', common));
          startButton.dispatchEvent(new MouseEvent('click', common));
          setTimeout(function() {
            if (window.__nra_resumeAllAudio) window.__nra_resumeAllAudio();
          }, 500);
        }

        var currentUrl = window.location.href;
        var bodyLen = (document.body && document.body.innerHTML) ? document.body.innerHTML.length : 0;

        return {
          hasRangeInput: hasRangeInput,
          rangeValue: rangeValue,
          hasStartButton: hasStartButton,
          currentUrl: currentUrl,
          hasSession: currentUrl.indexOf('session=') !== -1 || currentUrl.indexOf('#') !== -1,
          bodyLength: bodyLen
        };
      })();
      """;

  /**
   * JavaScript to set the volume slider value via the native HTMLInputElement setter so React's
   * synthetic event system picks up the change and updates the audio engine. Simply assigning
   * rangeInput.value does not trigger React's onChange handler.
   */
  private static final String SET_VOLUME_JS_TEMPLATE =
      """
      (function() {
        var rangeInput = document.querySelector('input[type="range"]');
        if (!rangeInput) return { success: false };
        var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        nativeSetter.call(rangeInput, %d);
        rangeInput.dispatchEvent(new Event('input', { bubbles: true }));
        return { success: true, value: parseInt(rangeInput.value) };
      })();
      """;

  private static OpenAudioMcService instance;

  // "You are already connected to the web client" desync recovery (Option A).
  private static final int MAX_ALREADY_CONNECTED_RETRIES = 4;
  private static final int ALREADY_CONNECTED_RETRY_DELAY_MS = 5000;
  private int alreadyConnectedRetries = 0;

  // Engine-start failures repeat on every server audio prompt / rejoin while a runtime is
  // missing; report each distinct reason once per cooldown instead of spamming chat and the log.
  private static final long ENGINE_FAILURE_RENOTIFY_MS = 60_000;
  private WebViewBridge.StartFailure lastEngineFailure;
  private long lastEngineFailureNotifyMs;

  private WebViewBridge bridge;
  private final AudioSessionLifecycle lifecycle = new AudioSessionLifecycle();
  private String savedSessionUrl;
  private volatile boolean isConnected;
  private volatile boolean isActive;
  private boolean hasReportedFailure;
  private int midSessionDropAttempts;
  private ScheduledExecutorService scheduler;
  private ScheduledFuture<?> pendingRecoveryTask;
  private ScheduledFuture<?> autoConnectTask;
  private ScheduledFuture<?> pendingCommandClearTask;
  private ScheduledFuture<?> reconnectFallbackTask;
  private int recoveryAttempts;
  private long monitorStartTimeMs;
  private int pageLoadCount;
  private volatile boolean pendingCommandConnect;
  private volatile int currentVolume = -1;

  private OpenAudioMcService() {}

  public static OpenAudioMcService getInstance() {
    if (instance == null) {
      instance = new OpenAudioMcService();
    }
    return instance;
  }

  /**
   * Returns true if the URL is an OpenAudioMC session URL. Accepts both the legacy path/query form
   * ({@code https://session.openaudiomc.net/...}) and the current hash-fragment form ({@code
   * https://session.openaudiomc.net#TOKEN}). The boundary check after the host rejects look-alike
   * domains such as {@code https://session.openaudiomc.net.example.com}.
   */
  public static boolean isOpenAudioMcUrl(String url) {
    if (url == null || !url.startsWith(URL_PREFIX)) {
      return false;
    }
    String rest = url.substring(URL_PREFIX.length());
    return rest.isEmpty() || rest.startsWith("/") || rest.startsWith("#");
  }

  /**
   * Scans a chat Component tree for OpenAudioMC session URLs in ClickEvents. Returns the first
   * matching URL, or null if none found.
   */
  public static String extractSessionUrl(Component component) {
    String url = extractUrlFromStyle(component.getStyle());
    if (url != null) {
      return url;
    }
    for (Component sibling : component.getSiblings()) {
      url = extractSessionUrl(sibling);
      if (url != null) {
        return url;
      }
    }
    return null;
  }

  private static String extractUrlFromStyle(Style style) {
    if (style == null) {
      return null;
    }
    ClickEvent clickEvent = style.getClickEvent();
    if (clickEvent instanceof ClickEvent.OpenUrl openUrl) {
      String value = openUrl.uri().toString();
      if (isOpenAudioMcUrl(value)) {
        return value;
      }
    }
    return null;
  }

  /**
   * Starts a new audio session. Launches the webview helper if needed, loads the session URL, and
   * begins DOM monitoring.
   */
  public synchronized void connect(String sessionUrl) {
    pendingCommandConnect = false;
    // Deduplicate: if already active with the same URL, ignore
    if (isActive && sessionUrl.equals(savedSessionUrl)) {
      LOGGER.debug("Ignoring duplicate connect for same URL");
      return;
    }
    if (isActive) {
      LOGGER.info("OpenAudioMC already active with different URL, disconnecting first");
      terminateSession("replaced-by-new-url");
    }

    LOGGER.info("Connecting to OpenAudioMC: {}", sessionUrl);

    // Each logical session owns a fresh helper/WebKit process context. Reusing a WKWebView after a
    // media or eval failure can retain WebKit media assertions and the old GPU process
    // indefinitely.
    WebViewBridge.StartFailure preflight = WebViewBridge.preflightCheck();
    if (preflight != null) {
      reportEngineFailure(preflight);
      return;
    }
    notifyUser("Starting audio engine...");
    String sessionId = UUID.randomUUID().toString();
    WebViewBridge newBridge = new WebViewBridge();
    newBridge.setSessionId(sessionId);
    newBridge.setExitListener(() -> onHelperChannelDisconnected(sessionId, newBridge));
    if (!newBridge.start()) {
      LOGGER.error("Failed to start WebView bridge — OpenAudioMC audio will not work");
      WebViewBridge.StartFailure failure = newBridge.getStartFailure();
      newBridge.stop();
      reportEngineFailure(failure);
      return;
    }
    bridge = newBridge;
    lifecycle.start(sessionId, newBridge);
    lastEngineFailure = null;
    if (!newBridge.isRunning()) {
      onHelperChannelDisconnected(sessionId, newBridge);
      return;
    }

    savedSessionUrl = sessionUrl;
    isActive = true;
    isConnected = false;
    hasReportedFailure = false;
    midSessionDropAttempts = 0;
    monitorStartTimeMs = System.currentTimeMillis();

    ReminderHandler.getInstance().setAudioConnected(false);

    pageLoadCount++;
    bridge.loadUrl(sessionUrl);
    LOGGER.info("Audio session loading (session={}, url={})", sessionId, sessionUrl);
    startMonitoring(sessionId);
  }

  /** Stops the current audio session and destroys its helper/WKWebView. */
  public synchronized void disconnect() {
    terminateSession("disconnect");
    recoveryAttempts = 0;
  }

  /** Attempts to reconnect using the last known session URL. */
  public synchronized void reconnect() {
    String reconnectUrl = savedSessionUrl;
    if (reconnectUrl == null) {
      return;
    }
    String oldSessionId = lifecycle.sessionId();
    LOGGER.info("Reconnecting audio session (session={})", oldSessionId);
    terminateSession("reconnect");
    connect(reconnectUrl);
  }

  /**
   * Lightweight check called when the app/game returns from background. Verifies the volume slider
   * still exists; if not, triggers a full reconnect.
   */
  public void softRefresh() {
    if (bridge == null || !isActive || !isConnected) {
      return;
    }
    bridge
        .evaluateJs(
            "(function(){ return {value: !!document.querySelector('input[type=\"range\"]')}; })()")
        .thenAccept(
            result -> {
              if (result != null && !result.optBoolean("value", true)) {
                LOGGER.info("Session dropped during suspension, reconnecting");
                reconnect();
              }
            });
  }

  /** Full cleanup: stops monitoring, kills the helper process, nulls all references. */
  public synchronized void dispose() {
    cancelAllScheduledTasks();
    lifecycle.minecraftStopping();
    resetSessionState();
    if (scheduler != null) {
      scheduler.shutdownNow();
      scheduler = null;
    }
    LOGGER.info("OpenAudioMC service disposed for Minecraft shutdown");
  }

  /**
   * Called when the server chat says "You are now connected with the audio client!" — confirms the
   * server recognizes the connection as live.
   */
  public void onServerConfirmedConnection() {
    midSessionDropAttempts = 0;
    alreadyConnectedRetries = 0;
  }

  /**
   * Called when the server chat says "You are already connected to the web client". Two cases:
   *
   * <ul>
   *   <li><b>Benign</b> — we really are connected ({@code isConnected}); the server is just
   *       rejecting a redundant /audio (e.g. a manual {@code /oa connect}). Treat as a
   *       confirmation.
   *   <li><b>Desync</b> — we are NOT connected but the server still thinks the old session's web
   *       client is attached. This happens after a {@code mid_session_drop}: our 3s slider poll
   *       noticed the relay drop before the server did, so reloading the same URL is refused with
   *       this message. The old code called {@link #onServerConfirmedConnection()} here, which
   *       reset {@code midSessionDropAttempts} every cycle and wedged the reconnect loop until the
   *       server's own ~95s timeout. Instead we force our relay socket closed (about:blank) so the
   *       server registers our departure and frees the session, then request a fresh /audio, with a
   *       bounded retry that falls back to waiting for the server timeout.
   * </ul>
   */
  public void onServerAlreadyConnected() {
    if (isConnected) {
      ReminderHandler.getInstance().setAudioConnected(true);
      onServerConfirmedConnection();
      return;
    }
    ReminderHandler.getInstance().setAudioConnected(false);
    if (alreadyConnectedRetries >= MAX_ALREADY_CONNECTED_RETRIES) {
      LOGGER.warn(
          "Server still reports already-connected after {} attempts; backing off and waiting for"
              + " the server's own session timeout",
          alreadyConnectedRetries);
      alreadyConnectedRetries = 0;
      return;
    }
    alreadyConnectedRetries++;
    LOGGER.info(
        "Already-connected desync (attempt {}/{}): dropping relay socket and requesting a fresh"
            + " session",
        alreadyConnectedRetries,
        MAX_ALREADY_CONNECTED_RETRIES);

    // Destroy the old helper before requesting a fresh signed URL.
    terminateSession("already-connected-desync");
    scheduleFreshSessionRequest("already-connected-desync", ALREADY_CONNECTED_RETRY_DELAY_MS);
  }

  /**
   * Called when the server chat says "Your audio session has been ended" — the server has
   * terminated the session, so reconnecting with the same URL won't help.
   */
  public synchronized void onServerEndedSession() {
    String endedSessionId = lifecycle.sessionId();
    LOGGER.info("Server ended the audio session (session={})", endedSessionId);
    cancelAllScheduledTasks();
    lifecycle.serverEnded();
    resetSessionState();
    notifyUser("Audio session ended by server. Requesting a fresh one...");
    scheduleFreshSessionRequest("server-ended", MONITOR_INTERVAL_MS);
  }

  /**
   * Called from the client JOIN handler. A few seconds after joining, requests an audio session via
   * /audio so auto-connect no longer depends on the server's own join message arriving. Skips
   * silently if a session is already active when the delay elapses (the server's join hook won).
   */
  public synchronized void autoConnectOnJoin() {
    ensureScheduler();
    if (autoConnectTask != null) {
      autoConnectTask.cancel(false);
    }
    autoConnectTask =
        scheduler.schedule(
            () -> {
              if (!isActive) {
                connectViaCommand();
              }
            },
            AUTO_CONNECT_DELAY_MS,
            TimeUnit.MILLISECONDS);
  }

  /**
   * Called from /oa connect. Sends /audio to the server to request a fresh session URL. The
   * ChatListenerMixin will detect the URL and call connect() automatically.
   */
  public synchronized void connectViaCommand() {
    if (isActive && isConnected) {
      notifyUser("Already connected to audio.");
      return;
    }
    if (isActive) {
      notifyUser("Already connecting to audio...");
      return;
    }

    pendingCommandConnect = true;
    Minecraft client = Minecraft.getInstance();
    if (client != null) {
      client.execute(
          () -> {
            if (client.player != null) {
              client.player.connection.sendCommand("audio");
            }
          });
    }

    // Clear the flag after 10 seconds if no URL was received
    ensureScheduler();
    if (pendingCommandClearTask != null) {
      pendingCommandClearTask.cancel(false);
    }
    pendingCommandClearTask =
        scheduler.schedule(() -> pendingCommandConnect = false, 10, TimeUnit.SECONDS);
  }

  /** Called from /oa disconnect. Stops the current audio session and notifies the user. */
  public synchronized void disconnectViaCommand() {
    if (!isActive && pendingRecoveryTask == null && !pendingCommandConnect) {
      notifyUser("Not connected to audio.");
      return;
    }
    disconnect();
    notifyUser("Audio disconnected.");
  }

  /**
   * Called from /oa reconnect. Tries to reload the saved session URL first. If not connected after
   * 30 seconds, falls back to disconnect + fresh /audio.
   */
  public synchronized void reconnectWithFallback() {
    if (savedSessionUrl != null && bridge != null && bridge.isRunning()) {
      notifyUser("Refreshing audio session...");
      reconnect();

      // Schedule fallback: if not connected after 30s, disconnect and request fresh URL
      ensureScheduler();
      if (reconnectFallbackTask != null) {
        reconnectFallbackTask.cancel(false);
      }
      reconnectFallbackTask =
          scheduler.schedule(
              () -> {
                if (!isConnected && isActive) {
                  LOGGER.info("Reconnect refresh failed after 30s, falling back to fresh /audio");
                  disconnect();
                  notifyUser("Refresh failed, requesting new session...");
                  connectViaCommand();
                }
              },
              30,
              TimeUnit.SECONDS);
    } else {
      notifyUser("No saved session, requesting new one...");
      connectViaCommand();
    }
  }

  /** Returns true if a /oa connect command is waiting for a session URL from the server. */
  public boolean isPendingCommandConnect() {
    return pendingCommandConnect;
  }

  public boolean isConnected() {
    return isConnected;
  }

  public boolean isActive() {
    return isActive;
  }

  /** Returns a counter that increments each time a page is loaded in the webview. */
  public int getPageLoadCount() {
    return pageLoadCount;
  }

  /** Returns the current volume (0-100), or -1 if unknown. */
  public int getCurrentVolume() {
    return currentVolume;
  }

  /**
   * Sets the volume silently (no chat notification). Used by the options screen slider, which fires
   * continuously while dragging.
   */
  public void setVolumeFromSlider(int volume) {
    if (volume < 0 || volume > 100 || bridge == null || !bridge.isRunning() || !isConnected) {
      return;
    }
    String js = String.format(SET_VOLUME_JS_TEMPLATE, volume);
    bridge
        .evaluateJs(js)
        .thenAccept(
            result -> {
              if (result != null && result.optBoolean("success", false)) {
                currentVolume = result.optInt("value", volume);
              }
            });
  }

  /**
   * Sets the volume on the OpenAudioMC slider (0-100). Injects JS to update the range input and
   * dispatch input/change events so the audio engine picks up the new value.
   */
  public void setVolume(int volume) {
    if (volume < 0 || volume > 100) {
      LOGGER.warn("Volume out of range: {}", volume);
      return;
    }
    if (bridge == null || !bridge.isRunning() || !isConnected) {
      notifyUser("Cannot set volume: not connected to audio.");
      return;
    }
    String js = String.format(SET_VOLUME_JS_TEMPLATE, volume);
    bridge
        .evaluateJs(js)
        .thenAccept(
            result -> {
              if (result != null && result.optBoolean("success", false)) {
                currentVolume = result.optInt("value", volume);
                notifyUser("Volume set to " + currentVolume + "%");
              } else {
                notifyUser("Failed to set volume — slider not found.");
              }
            });
  }

  private void startMonitoring(String sessionId) {
    scheduleMonitor(sessionId, MONITOR_INTERVAL_MS);
  }

  private void scheduleMonitor(String sessionId, long delayMs) {
    ensureScheduler();
    lifecycle.setMonitorTask(
        scheduler.schedule(() -> monitorSession(sessionId), delayMs, TimeUnit.MILLISECONDS));
  }

  /**
   * Runs at most one eval at a time. The next poll is scheduled only after this eval completes, so
   * a wedged WebKit main frame cannot accumulate a new runJavaScript request every three seconds.
   */
  private void monitorSession(String sessionId) {
    WebViewBridge currentBridge;
    synchronized (this) {
      if (!sessionId.equals(lifecycle.sessionId())
          || bridge == null
          || !bridge.isRunning()
          || !isActive) {
        return;
      }
      currentBridge = bridge;
    }

    currentBridge
        .evaluateJs(STATUS_CHECK_JS)
        .whenComplete(
            (result, failure) -> {
              if (failure != null) {
                handleMonitorFailure(sessionId, failure);
                return;
              }
              synchronized (OpenAudioMcService.this) {
                if (!sessionId.equals(lifecycle.sessionId()) || !isActive) {
                  return;
                }
                lifecycle.monitorSucceeded();
                handleMonitorResult(sessionId, result);
                if (sessionId.equals(lifecycle.sessionId()) && isActive) {
                  scheduleMonitor(sessionId, MONITOR_INTERVAL_MS);
                }
              }
            });
  }

  private synchronized void handleMonitorFailure(String sessionId, Throwable failure) {
    if (!sessionId.equals(lifecycle.sessionId()) || !isActive) {
      return;
    }
    AudioSessionLifecycle.MonitorFailureDecision decision = lifecycle.monitorFailed(failure);
    if (decision.retry()) {
      scheduleMonitor(sessionId, decision.delayMs());
      return;
    }

    // monitorFailed closed the session-owned helper after the bounded retry limit.
    cancelAllScheduledTasks();
    resetSessionState();
    notifyUser(
        "Audio engine stopped after repeated monitor timeouts. Requesting a fresh session...");
    scheduleRecoveryWithBackoff("monitor-timeout");
  }

  private synchronized void handleMonitorResult(String sessionId, JSONObject result) {
    if (result == null || !sessionId.equals(lifecycle.sessionId()) || !isActive) {
      return;
    }

    boolean hasRangeInput = result.optBoolean("hasRangeInput", false);
    boolean hasStartButton = result.optBoolean("hasStartButton", false);
    String currentUrl = result.optString("currentUrl", "");
    boolean hasSession = result.optBoolean("hasSession", false);
    boolean wasConnected = isConnected;

    if (hasRangeInput) {
      // Track volume from the range input
      int volume = result.optInt("rangeValue", -1);
      if (volume >= 0) {
        currentVolume = volume;
      }

      // Audio session is active
      if (!isConnected) {
        LOGGER.info("OpenAudioMC audio session connected (session={})", sessionId);
        isConnected = true;
        recoveryAttempts = 0;
        ReminderHandler.getInstance().setAudioConnected(true);
        notifyUser(
            "Audio connected! Volume: "
                + (volume >= 0 ? volume + "%" : "unknown")
                + ". Adjust via /volume in-game or Options > Music & Sounds.");
      }
      // Update saved URL if it changed
      if (hasSession && !currentUrl.equals(savedSessionUrl)) {
        savedSessionUrl = currentUrl;
      }
    } else if (hasStartButton) {
      // STATUS_CHECK_JS clicked the button in the same serialized eval.
      LOGGER.info("Auto-clicking 'Start Audio Session' button");
    } else {
      // Check for connection timeout
      long elapsed = System.currentTimeMillis() - monitorStartTimeMs;
      if (elapsed >= CONNECTION_TIMEOUT_MS && !isConnected && !hasReportedFailure) {
        handleFailure("timeout");
      }
    }

    // Detect mid-session drop (was connected but volume slider disappeared).
    if (wasConnected && !hasRangeInput) {
      midSessionDropAttempts++;
      LOGGER.warn(
          "Audio session dropped; destroying old helper before recovery (session={}, attempt={}/{})",
          sessionId,
          midSessionDropAttempts,
          MAX_MID_SESSION_DROP_ATTEMPTS);
      terminateSession("mid-session-drop");
      scheduleRecoveryWithBackoff("mid-session-drop");
    }
  }

  private synchronized void handleFailure(String reason) {
    LOGGER.error("OpenAudioMC connection failed: {}", reason);
    hasReportedFailure = true;
    terminateSession(reason);
  }

  private synchronized void onHelperChannelDisconnected(
      String sessionId, WebViewBridge disconnectedBridge) {
    if (!sessionId.equals(lifecycle.sessionId()) || bridge != disconnectedBridge) {
      return;
    }
    LOGGER.warn("Audio helper channel disconnected unexpectedly (session={})", sessionId);
    cancelAllScheduledTasks();
    lifecycle.helperDisconnected();
    resetSessionState();
    scheduleRecoveryWithBackoff("helper-channel-disconnected");
  }

  /** Called when leaving any multiplayer server; never schedules an automatic reconnect. */
  public synchronized void onLeaveServer() {
    cancelAllScheduledTasks();
    lifecycle.leaveServer();
    resetSessionState();
    recoveryAttempts = 0;
  }

  private synchronized void terminateSession(String reason) {
    cancelAllScheduledTasks();
    lifecycle.stop(reason);
    resetSessionState();
  }

  private void resetSessionState() {
    bridge = null;
    savedSessionUrl = null;
    isActive = false;
    isConnected = false;
    currentVolume = -1;
    pendingCommandConnect = false;
    ReminderHandler.getInstance().setAudioConnected(false);
  }

  private void scheduleRecoveryWithBackoff(String reason) {
    if (recoveryAttempts >= MAX_MID_SESSION_DROP_ATTEMPTS) {
      LOGGER.error(
          "Audio recovery limit reached; leaving helper stopped (reason={}, attempts={})",
          reason,
          recoveryAttempts);
      notifyUser("Audio recovery stopped after repeated failures. Use /oa connect to try again.");
      return;
    }
    recoveryAttempts++;
    long delayMs = MONITOR_INTERVAL_MS * (1L << (recoveryAttempts - 1));
    scheduleFreshSessionRequest(reason, delayMs);
  }

  private void scheduleFreshSessionRequest(String reason, long delayMs) {
    ensureScheduler();
    cancelPendingRecovery();
    int attempt = recoveryAttempts;
    LOGGER.info(
        "Scheduling fresh audio session (reason={}, attempt={}, delayMs={})",
        reason,
        attempt,
        delayMs);
    pendingRecoveryTask =
        scheduler.schedule(
            () -> {
              synchronized (OpenAudioMcService.this) {
                pendingRecoveryTask = null;
              }
              connectViaCommand();
            },
            delayMs,
            TimeUnit.MILLISECONDS);
  }

  private void cancelPendingRecovery() {
    if (pendingRecoveryTask != null) {
      pendingRecoveryTask.cancel(false);
      pendingRecoveryTask = null;
    }
  }

  private void cancelAllScheduledTasks() {
    cancelPendingRecovery();
    if (autoConnectTask != null) {
      autoConnectTask.cancel(false);
      autoConnectTask = null;
    }
    if (pendingCommandClearTask != null) {
      pendingCommandClearTask.cancel(false);
      pendingCommandClearTask = null;
    }
    if (reconnectFallbackTask != null) {
      reconnectFallbackTask.cancel(false);
      reconnectFallbackTask = null;
    }
  }

  private void ensureScheduler() {
    if (scheduler == null || scheduler.isShutdown()) {
      scheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "OpenAudioMC-Monitor");
                t.setDaemon(true);
                return t;
              });
    }
  }

  /**
   * Reports an audio-engine startup failure with actionable guidance (which runtime to install,
   * with a clickable link). The same reason is re-reported at most once per {@link
   * #ENGINE_FAILURE_RENOTIFY_MS}; suppressed repeats only log at debug so a missing runtime doesn't
   * flood chat and the log on every server audio prompt.
   */
  private void reportEngineFailure(WebViewBridge.StartFailure failure) {
    if (failure == null) {
      failure = WebViewBridge.StartFailure.HELPER_LAUNCH_FAILED;
    }
    long now = System.currentTimeMillis();
    if (failure == lastEngineFailure
        && now - lastEngineFailureNotifyMs < ENGINE_FAILURE_RENOTIFY_MS) {
      LOGGER.debug("Audio engine still unavailable ({}), suppressing repeat notification", failure);
      return;
    }
    lastEngineFailure = failure;
    lastEngineFailureNotifyMs = now;
    LOGGER.error("Audio engine unavailable: {} \u2014 {}", failure, failure.userMessage());
    notifyUser(failure.userMessage(), failure.helpUrl());
  }

  private void notifyUser(String message) {
    notifyUser(message, null);
  }

  private void notifyUser(String message, String url) {
    Minecraft client = Minecraft.getInstance();
    if (client == null) {
      return;
    }
    MutableComponent text = Component.literal("\u00A76\u2728 \u00A7e[IMF] \u00A7f" + message);
    if (url != null) {
      text.append(
          Component.literal("\u00A7b\u00A7n" + url)
              .withStyle(style -> style.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))));
    }
    client.execute(() -> client.gui.getChat().addMessage(text));
  }
}
