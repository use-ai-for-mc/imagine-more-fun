package com.chenweikeng.imf.nra.audio;

import com.chenweikeng.imf.nra.handler.ReminderHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
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

  private static final int MAX_RECONNECT_ATTEMPTS = 3;
  private static final int MAX_MID_SESSION_DROP_ATTEMPTS = 3;
  private static final int MONITOR_INTERVAL_MS = 3000;
  private static final int CONNECTION_TIMEOUT_MS = 60000;
  private static final int AUTO_CONNECT_DELAY_MS = 5000;

  /** JavaScript injected every 3 seconds to check DOM state. */
  private static final String STATUS_CHECK_JS =
      """
      (function() {
        var rangeInput = document.querySelector('input[type="range"]');
        var hasRangeInput = !!rangeInput;
        var rangeValue = hasRangeInput ? parseInt(rangeInput.value) : -1;

        var buttons = Array.prototype.slice.call(document.querySelectorAll('button, [role="button"]'));
        var hasStartButton = buttons.some(
          function(el) { return (el.outerText || el.textContent || '').trim().toLowerCase() === 'start audio session'; }
        );

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

  /** JavaScript to auto-click the "Start Audio Session" button using synthetic events. */
  private static final String CLICK_START_JS =
      """
      (function() {
        var buttons = Array.prototype.slice.call(document.querySelectorAll('button, [role="button"]'));
        var btn = buttons.find(
          function(b) { return (b.outerText || b.textContent || '').trim().toLowerCase() === 'start audio session'; }
        );
        if (!btn) return { clicked: false };

        var rect = btn.getBoundingClientRect();
        var cx = rect.left + rect.width / 2;
        var cy = rect.top + rect.height / 2;
        var common = { bubbles: true, cancelable: true, view: window,
                       clientX: cx, clientY: cy, screenX: cx, screenY: cy,
                       button: 0, buttons: 1 };

        try {
          btn.dispatchEvent(new PointerEvent('pointerdown', common));
          btn.dispatchEvent(new PointerEvent('pointerup', common));
        } catch(pe) {}

        btn.dispatchEvent(new MouseEvent('mousedown', common));
        btn.dispatchEvent(new MouseEvent('mouseup', common));
        btn.dispatchEvent(new MouseEvent('click', common));

        // Force-resume all AudioContexts after a short delay
        setTimeout(function() {
          if (window.__nra_resumeAllAudio) window.__nra_resumeAllAudio();
        }, 500);

        return { clicked: true };
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

  private WebViewBridge bridge;
  private String savedSessionUrl;
  private boolean isConnected;
  private boolean isActive;
  private boolean hasReportedFailure;
  private int reconnectAttempts;
  private int midSessionDropAttempts;
  private volatile boolean serverEndedSession;
  private ScheduledExecutorService scheduler;
  private ScheduledFuture<?> monitorTask;
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
      disconnect();
    }

    LOGGER.info("Connecting to OpenAudioMC: {}", sessionUrl);

    if (bridge == null) {
      notifyUser("Starting audio engine...");
      bridge = new WebViewBridge();
      if (!bridge.start()) {
        LOGGER.error("Failed to start WebView bridge — OpenAudioMC audio will not work");
        notifyUser("Failed to start audio engine.");
        return;
      }
    }

    savedSessionUrl = sessionUrl;
    isActive = true;
    isConnected = false;
    hasReportedFailure = false;
    reconnectAttempts = 0;
    midSessionDropAttempts = 0;
    serverEndedSession = false;
    monitorStartTimeMs = System.currentTimeMillis();

    ReminderHandler.getInstance().setAudioConnected(false);

    pageLoadCount++;
    bridge.loadUrl(sessionUrl);
    startMonitoring();
  }

  /** Stops the current audio session. Navigates the webview to about:blank (stops audio). */
  public void disconnect() {
    LOGGER.info("Disconnecting OpenAudioMC");
    stopMonitoring();
    isActive = false;
    isConnected = false;
    currentVolume = -1;
    ReminderHandler.getInstance().setAudioConnected(false);

    if (bridge != null) {
      bridge.loadUrl("about:blank");
    }
  }

  /** Attempts to reconnect using the last known session URL. */
  public void reconnect() {
    if (savedSessionUrl == null) {
      return;
    }
    LOGGER.info("Reconnecting to OpenAudioMC");
    isConnected = false;
    hasReportedFailure = false;
    monitorStartTimeMs = System.currentTimeMillis();

    if (bridge != null) {
      pageLoadCount++;
      bridge.loadUrl(savedSessionUrl);
    }
    startMonitoring();
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
  public void dispose() {
    stopMonitoring();
    if (scheduler != null) {
      scheduler.shutdownNow();
      scheduler = null;
    }
    if (bridge != null) {
      bridge.stop();
      bridge = null;
    }
    savedSessionUrl = null;
    isActive = false;
    isConnected = false;
  }

  /**
   * Called when the server chat says "You are now connected with the audio client!" — confirms the
   * server recognizes the connection as live.
   */
  public void onServerConfirmedConnection() {
    serverEndedSession = false;
    midSessionDropAttempts = 0;
  }

  /**
   * Called when the server chat says "Your audio session has been ended" — the server has
   * terminated the session, so reconnecting with the same URL won't help.
   */
  public void onServerEndedSession() {
    LOGGER.info("Server ended the audio session");
    serverEndedSession = true;
    if (isConnected) {
      isConnected = false;
      ReminderHandler.getInstance().setAudioConnected(false);
    }
  }

  /**
   * Called from the client JOIN handler. A few seconds after joining, requests an audio session via
   * /audio so auto-connect no longer depends on the server's own join message arriving. Skips
   * silently if a session is already active when the delay elapses (the server's join hook won).
   */
  public void autoConnectOnJoin() {
    if (scheduler == null || scheduler.isShutdown()) {
      scheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "OpenAudioMC-Monitor");
                t.setDaemon(true);
                return t;
              });
    }
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
  public void connectViaCommand() {
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
    if (scheduler == null || scheduler.isShutdown()) {
      scheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "OpenAudioMC-Monitor");
                t.setDaemon(true);
                return t;
              });
    }
    scheduler.schedule(() -> pendingCommandConnect = false, 10, TimeUnit.SECONDS);
  }

  /** Called from /oa disconnect. Stops the current audio session and notifies the user. */
  public void disconnectViaCommand() {
    if (!isActive) {
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
  public void reconnectWithFallback() {
    if (savedSessionUrl != null && bridge != null && bridge.isRunning()) {
      notifyUser("Refreshing audio session...");
      reconnect();

      // Schedule fallback: if not connected after 30s, disconnect and request fresh URL
      if (scheduler == null || scheduler.isShutdown()) {
        scheduler =
            Executors.newSingleThreadScheduledExecutor(
                r -> {
                  Thread t = new Thread(r, "OpenAudioMC-Monitor");
                  t.setDaemon(true);
                  return t;
                });
      }
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

  /**
   * Evaluates JavaScript in the webview. Returns a future that completes with the result, or null
   * if the bridge is not available.
   */
  public CompletableFuture<JSONObject> evaluateJs(String js) {
    if (bridge == null || !bridge.isRunning()) {
      return CompletableFuture.completedFuture(null);
    }
    return bridge.evaluateJs(js);
  }

  // ---- window.__nra_audio bridge ---------------------------------------------------------------
  //
  // The macOS WebViewHelper installs a per-element audio registry at document-start
  // (window.__nra_audio). These helpers wrap evaluateJs to expose enumerate / stop /
  // per-element-volume control on top of the React master-volume slider.
  // Result shape: { value: [...summaries] } for list*, { value: <boolean> } for others.

  private static final String AUDIO_LIST_JS =
      "(function(){return window.__nra_audio?window.__nra_audio.list():[];})()";
  private static final String AUDIO_LIST_ALL_JS =
      "(function(){return window.__nra_audio?window.__nra_audio.listAll():[];})()";
  private static final String AUDIO_NAMES_JS =
      "(function(){return window.__nra_audio?window.__nra_audio.names():[];})()";
  private static final String AUDIO_STOP_TPL =
      "(function(){return !!(window.__nra_audio&&window.__nra_audio.stop(%d));})()";
  private static final String AUDIO_STOP_ALL_JS =
      "(function(){return window.__nra_audio?window.__nra_audio.stopAll():0;})()";
  private static final String AUDIO_SET_VOLUME_TPL =
      "(function(){return !!(window.__nra_audio&&window.__nra_audio.setVolume(%d,%s));})()";

  /** Lists the audio elements the registry knows about. Skips data: URLs and ended elements. */
  public CompletableFuture<JSONObject> listAudio() {
    return evaluateJs(AUDIO_LIST_JS);
  }

  /** Lists every audio element including data: URLs (the NoSleep video) and ended elements. */
  public CompletableFuture<JSONObject> listAudioAll() {
    return evaluateJs(AUDIO_LIST_ALL_JS);
  }

  /** Stops one element by registry id (pauses and seeks to 0). */
  public CompletableFuture<JSONObject> stopAudio(int id) {
    return evaluateJs(String.format(AUDIO_STOP_TPL, id));
  }

  /** Pauses every non-data: element (preserves the NoSleep keep-alive video). */
  public CompletableFuture<JSONObject> stopAllAudio() {
    return evaluateJs(AUDIO_STOP_ALL_JS);
  }

  /** Sets per-element volume (0..1). The engine may overwrite this on the next tick. */
  public CompletableFuture<JSONObject> setAudioVolume(int id, double v01) {
    double clamped = Math.max(0.0, Math.min(1.0, v01));
    return evaluateJs(String.format(java.util.Locale.ROOT, AUDIO_SET_VOLUME_TPL, id, clamped));
  }

  /**
   * Returns the registry list enriched with the server-assigned {@code soundId} for each element
   * (recovered from socket.io {@code ClientCreateMediaPayload} frames captured by the WS hook).
   * Tracks whose CREATE_MEDIA frame predates the hook will have {@code soundId: null}.
   */
  public CompletableFuture<JSONObject> namesAudio() {
    return evaluateJs(AUDIO_NAMES_JS);
  }

  /** Last n WKWebView console messages buffered by the bridge. Empty if not connected. */
  public java.util.List<WebViewBridge.ConsoleLog> recentConsoleLogs(int n) {
    if (bridge == null) return java.util.List.of();
    return bridge.recentConsoleLogs(n);
  }

  private void startMonitoring() {
    stopMonitoring();
    if (scheduler == null || scheduler.isShutdown()) {
      scheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "OpenAudioMC-Monitor");
                t.setDaemon(true);
                return t;
              });
    }
    monitorTask =
        scheduler.scheduleAtFixedRate(
            this::monitorSession, MONITOR_INTERVAL_MS, MONITOR_INTERVAL_MS, TimeUnit.MILLISECONDS);
  }

  private void stopMonitoring() {
    if (monitorTask != null) {
      monitorTask.cancel(false);
      monitorTask = null;
    }
  }

  private void monitorSession() {
    if (bridge == null || !bridge.isRunning() || !isActive) {
      return;
    }

    bridge
        .evaluateJs(STATUS_CHECK_JS)
        .thenAccept(this::handleMonitorResult)
        .exceptionally(
            ex -> {
              LOGGER.error("Monitor eval failed", ex);
              return null;
            });
  }

  private void handleMonitorResult(JSONObject result) {
    if (result == null || !isActive) {
      return;
    }

    boolean hasRangeInput = result.optBoolean("hasRangeInput", false);
    boolean hasStartButton = result.optBoolean("hasStartButton", false);
    String currentUrl = result.optString("currentUrl", "");
    boolean hasSession = result.optBoolean("hasSession", false);
    boolean wasConnected = isConnected;

    // Server told us the session is over (via the "Your audio session has been ended" chat
    // message). React typically keeps the volume slider mounted for ~3 s after the underlying
    // socket dies, so a naive hasRangeInput check here would re-declare us connected and fire
    // a misleading "Audio connected!" notification. Treat this as a soft terminate that drops
    // the dead session URL but keeps the bridge subprocess alive, and request a fresh /audio
    // so OpenAudioMC issues a brand-new signed URL.
    if (serverEndedSession) {
      handleServerEndedReconnect();
      return;
    }

    if (hasRangeInput) {
      // Track volume from the range input
      int volume = result.optInt("rangeValue", -1);
      if (volume >= 0) {
        currentVolume = volume;
      }

      // Audio session is active
      if (!isConnected) {
        LOGGER.info("OpenAudioMC audio session connected");
        isConnected = true;
        reconnectAttempts = 0;
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
      // Page loaded but session not started — auto-click the button
      LOGGER.info("Auto-clicking 'Start Audio Session' button");
      bridge.evaluateJs(CLICK_START_JS);
    } else if (!hasSession
        && !isOpenAudioMcUrl(currentUrl)
        && savedSessionUrl != null
        && wasConnected) {
      // Session was lost (page navigated away or crashed)
      if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
        reconnectAttempts++;
        LOGGER.warn(
            "Session lost, reconnecting (attempt {}/{})",
            reconnectAttempts,
            MAX_RECONNECT_ATTEMPTS);
        isConnected = false;
        ReminderHandler.getInstance().setAudioConnected(false);
        monitorStartTimeMs = System.currentTimeMillis();
        bridge.loadUrl(savedSessionUrl);
      } else {
        handleFailure("max_reconnect");
      }
    } else {
      // Check for connection timeout
      long elapsed = System.currentTimeMillis() - monitorStartTimeMs;
      if (elapsed >= CONNECTION_TIMEOUT_MS && !isConnected && !hasReportedFailure) {
        handleFailure("timeout");
      }
    }

    // Detect mid-session drop (was connected but volume slider disappeared).
    // serverEndedSession is handled by the early-return above, so it can't reach here.
    if (wasConnected && !hasRangeInput) {
      isConnected = false;
      ReminderHandler.getInstance().setAudioConnected(false);

      if (midSessionDropAttempts < MAX_MID_SESSION_DROP_ATTEMPTS) {
        midSessionDropAttempts++;
        LOGGER.warn(
            "Audio session dropped, reconnecting (attempt {}/{})",
            midSessionDropAttempts,
            MAX_MID_SESSION_DROP_ATTEMPTS);
        monitorStartTimeMs = System.currentTimeMillis();
        bridge.loadUrl(savedSessionUrl);
      } else {
        LOGGER.error("Audio session dropped too many times, giving up");
        handleFailure("mid_session_drop");
        notifyUser(
            "Audio session lost after multiple reconnection attempts. Use /audio to reconnect.");
      }
    }
  }

  /**
   * Soft-terminates the current session in response to a "Your audio session has been ended" chat
   * message from the server, then schedules a single {@code /audio} request so the server issues a
   * fresh signed URL. The bridge subprocess is kept alive — the new URL will be loaded into the
   * existing WKWebView. If the user explicitly meant to end audio (e.g. they ran {@code /audio
   * off}) they can run {@code /oa disconnect} within the retry window; the fresh /audio request
   * will then no-op because savedSessionUrl is null and isActive=false.
   */
  private void handleServerEndedReconnect() {
    LOGGER.info("Server ended the audio session; soft-terminating and requesting fresh /audio");
    stopMonitoring();
    isActive = false;
    isConnected = false;
    currentVolume = -1;
    savedSessionUrl = null;
    serverEndedSession = false;
    midSessionDropAttempts = 0;
    reconnectAttempts = 0;
    ReminderHandler.getInstance().setAudioConnected(false);
    notifyUser("Audio session ended by server. Requesting a fresh one...");

    if (scheduler == null || scheduler.isShutdown()) {
      scheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "OpenAudioMC-Monitor");
                t.setDaemon(true);
                return t;
              });
    }
    // Give the server 3 s to settle before asking for a new session URL.
    scheduler.schedule(this::connectViaCommand, 3, TimeUnit.SECONDS);
  }

  private void handleFailure(String reason) {
    LOGGER.error("OpenAudioMC connection failed: {}", reason);
    hasReportedFailure = true;
    isActive = false;
    isConnected = false;
    currentVolume = -1;
    stopMonitoring();
    ReminderHandler.getInstance().setAudioConnected(false);

    if (bridge != null) {
      bridge.stop();
      bridge = null;
    }
    savedSessionUrl = null;
  }

  private void notifyUser(String message) {
    Minecraft client = Minecraft.getInstance();
    if (client != null) {
      client.execute(
          () ->
              client
                  .gui
                  .getChat()
                  .addMessage(Component.literal("\u00A76\u2728 \u00A7e[IMF] \u00A7f" + message)));
    }
  }
}
