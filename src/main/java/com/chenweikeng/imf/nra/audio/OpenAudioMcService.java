package com.chenweikeng.imf.nra.audio;

import com.chenweikeng.imf.nra.handler.ReminderHandler;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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

  private static final String URL_LOG_HOST = "session.openaudiomc.net";

  private static final int MAX_MID_SESSION_DROP_ATTEMPTS = 3;
  private static final int MAX_RECOVERY_ATTEMPTS = 6;
  private static final int MONITOR_INTERVAL_MS = 3000;
  private static final int CONNECTION_TIMEOUT_MS = 60000;
  static final long SESSION_OFFER_TIMEOUT_MS = 10_000;
  // ImagineFun auto-prompts an audio session a few seconds after join (its own /audio).
  // Our fallback request must wait long enough for that server-provided link to arrive and
  // run connect() (which flips isActive), otherwise both fire and the server mints two
  // separate sessions. Observed server latency is ~4-5s; 12s gives a comfortable margin.
  private static final int AUTO_CONNECT_DELAY_MS = 12000;

  /** JavaScript injected every 3 seconds to check DOM state. */
  private static final String STATUS_CHECK_JS =
      """
      (function() {
        // A hidden WKWebView can suspend a long-idle AudioContext after the one-time startup
        // resume. Spatial ride speakers use that context while ordinary media elements do not,
        // so keep every live context running as part of the serialized health poll.
        if (window.__nra_resumeAllAudio) window.__nra_resumeAllAudio();

        var rangeInput = document.querySelector('input[type="range"]');
        var hasRangeInput = !!rangeInput;
        var rangeValue = hasRangeInput ? parseInt(rangeInput.value) : -1;
        if (hasRangeInput && window.__nra_commit_master_volume) {
          var preferredVolume = parseInt(window.__nra_preferred_volume);
          var volumeGateActive = window.__nra_volume_gate_active === true;
          if (!volumeGateActive || rangeValue === preferredVolume) {
            window.__nra_commit_master_volume(rangeValue);
          }
        }

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
          bodyLength: bodyLen,
          mediaHealth: window.__nra_media_health ? window.__nra_media_health() : null
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
        rangeInput.dispatchEvent(new Event('change', { bubbles: true }));
        var actualValue = parseInt(rangeInput.value);
        if (window.__nra_commit_master_volume) {
          window.__nra_commit_master_volume(actualValue);
        }
        return { success: true, value: actualValue };
      })();
      """;

  // "You are already connected to the web client" desync recovery (Option A).
  private static final int MAX_ALREADY_CONNECTED_RETRIES = 4;
  private static final int ALREADY_CONNECTED_RETRY_DELAY_MS = 5000;
  private int alreadyConnectedRetries = 0;

  // Engine-start failures repeat on every server audio prompt / rejoin while a runtime is
  // missing; report each distinct reason once per cooldown instead of spamming chat and the log.
  private static final long ENGINE_FAILURE_RENOTIFY_MS = 60_000;
  static final long DUPLICATE_STARTUP_OFFER_WINDOW_MS = 30_000;
  private WebViewBridge.StartFailure lastEngineFailure;
  private long lastEngineFailureNotifyMs;
  private String lastStartupOfferUrl;
  private long lastStartupOfferAtMs;

  private WebViewBridge bridge;
  // A cold WKWebView can take tens of seconds to report ready. This reference reserves the one
  // allowed startup slot, but start() itself must always run without holding this service's
  // monitor: chat handling and the Minecraft render/input loop query synchronized service state.
  private WebViewBridge startingBridge;
  private final AudioSessionLifecycle lifecycle = new AudioSessionLifecycle();
  private String savedSessionUrl;
  private volatile boolean isConnected;
  private volatile boolean isActive;
  private boolean hasReportedFailure;
  private int midSessionDropAttempts;
  private ScheduledExecutorService scheduler;
  private volatile ScheduledFuture<?> pendingRecoveryTask;
  private ScheduledFuture<?> autoConnectTask;
  private ScheduledFuture<?> pendingSessionOfferTimeoutTask;
  private ScheduledFuture<?> reconnectFallbackTask;
  private int recoveryAttempts;
  private long monitorStartTimeMs;
  private volatile long helperGeneration;
  private long lastHealthLogMs;
  private volatile int createdMediaCount;
  private volatile int disposedMediaCount;
  private volatile int liveMediaCount;
  private volatile int recentMediaFailureCount;
  private volatile long lastSuccessfulPlayTimeMs;
  private final SessionOfferTracker sessionOfferTracker = new SessionOfferTracker();
  // User/config intent survives individual URLs, helpers and server-side session termination.
  // Network/server disconnects tear down resources but preserve this intent for the next JOIN.
  // Only an explicit audio disconnect or Minecraft shutdown clears it.
  private volatile boolean connectionDesired;
  private volatile int currentVolume = -1;
  private final AudioVolumeStore volumeStore;
  private final AudioVolumeState volumeState;
  private final Supplier<? extends WebViewBridge> bridgeFactory;

  private static final class InstanceHolder {
    private static final OpenAudioMcService INSTANCE = new OpenAudioMcService();
  }

  private OpenAudioMcService() {
    this(new AudioVolumeStore(), WebViewBridge::new);
  }

  OpenAudioMcService(
      AudioVolumeStore volumeStore, Supplier<? extends WebViewBridge> bridgeFactory) {
    this.volumeStore = volumeStore;
    this.bridgeFactory = bridgeFactory;
    int persistedVolume = volumeStore.load();
    volumeState = new AudioVolumeState(persistedVolume);
    currentVolume = persistedVolume;
    if (AudioVolumeState.isValid(persistedVolume)) {
      LOGGER.info("Loaded persisted OpenAudioMC volume: {}%", persistedVolume);
    }
  }

  public static OpenAudioMcService getInstance() {
    return InstanceHolder.INSTANCE;
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
   * Returns a non-sensitive description suitable for logs. Session paths, query values and
   * fragments may contain bearer material, so this method reports only the fixed host and URL
   * shape.
   */
  static String describeSessionUrlForLog(String url) {
    if (url == null) {
      return "host=invalid, shape=unparseable";
    }
    try {
      URI parsed = URI.create(url);
      String host = URL_LOG_HOST.equalsIgnoreCase(parsed.getHost()) ? URL_LOG_HOST : "unexpected";
      String path = parsed.getRawPath();
      StringBuilder shape = new StringBuilder();
      if (path != null && !path.isEmpty() && !"/".equals(path)) {
        shape.append("path");
      }
      if (parsed.getRawQuery() != null) {
        if (!shape.isEmpty()) {
          shape.append('+');
        }
        shape.append("query");
      }
      if (parsed.getRawFragment() != null) {
        if (!shape.isEmpty()) {
          shape.append('+');
        }
        shape.append("fragment");
      }
      if (shape.isEmpty()) {
        shape.append("bare");
      }
      return "host=" + host + ", shape=" + shape;
    } catch (IllegalArgumentException ignored) {
      return "host=invalid, shape=unparseable";
    }
  }

  /** Removes any OpenAudioMC bearer URL embedded in a helper-provided diagnostic message. */
  static String sanitizeLogMessage(String message) {
    if (message == null || message.isEmpty()) {
      return "";
    }
    return message.replaceAll("(?i)(?:https?|wss?)://\\S+", "[url redacted]");
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
  public void connect(String sessionUrl) {
    final String sessionId;
    final long generation;
    final WebViewBridge newBridge;

    // Reserve a single startup attempt while holding the monitor only for cheap state changes.
    // WebViewBridge.start() below launches a process and can wait 30 seconds for WKWebView; holding
    // this lock across that wait blocks render-thread chat handling and therefore mouse input.
    synchronized (this) {
      boolean explicitlyRequested = sessionOfferTracker.accept();
      cancelSessionOfferTimeoutTask();
      long now = System.currentTimeMillis();
      if (shouldIgnoreStartupOffer(
          explicitlyRequested, sessionUrl, lastStartupOfferUrl, lastStartupOfferAtMs, now)) {
        LOGGER.debug(
            "Ignoring duplicate queued OpenAudioMC session offer during startup cooldown ({})",
            describeSessionUrlForLog(sessionUrl));
        return;
      }
      if (startingBridge != null) {
        LOGGER.debug(
            "Ignoring OpenAudioMC session offer while helper startup is already in progress");
        return;
      }
      // A different URL offered while a session is live is a stale/duplicate server offer. All
      // intentional replacement paths tear down the active session before requesting a new URL.
      if (isActive) {
        if (sessionUrl.equals(savedSessionUrl)) {
          LOGGER.debug("Ignoring duplicate connect for same URL");
        } else {
          LOGGER.debug("Ignoring stale OpenAudioMC session offer while another session is active");
        }
        return;
      }

      // A usable offer supersedes a delayed fallback request. Do this only after duplicate/stale
      // offers have been rejected so an old server message cannot cancel intentional recovery.
      cancelPendingRecovery();
      lastStartupOfferUrl = sessionUrl;
      lastStartupOfferAtMs = now;
      sessionId = UUID.randomUUID().toString();
      generation = ++helperGeneration;
      newBridge = bridgeFactory.get();
      newBridge.setSessionId(sessionId);
      newBridge.setHelperGeneration(generation);
      newBridge.setExitListener(() -> onHelperChannelDisconnected(sessionId, newBridge));
      newBridge.setEngineFailureListener(
          reason -> onHelperEngineFailure(sessionId, newBridge, reason));
      startingBridge = newBridge;
    }

    LOGGER.info("Connecting to OpenAudioMC ({})", describeSessionUrlForLog(sessionUrl));

    // Each logical session owns a fresh helper/WebKit process context. Reusing a WKWebView after a
    // media or eval failure can retain WebKit media assertions and the old GPU process
    // indefinitely. Both preflight and startup deliberately execute outside the service monitor.
    WebViewBridge.StartFailure preflight = WebViewBridge.preflightCheck();
    if (preflight != null) {
      finishFailedStartup(newBridge, preflight);
      return;
    }
    notifyUser("Starting audio engine...");
    if (!newBridge.start()) {
      LOGGER.error("Failed to start WebView bridge — OpenAudioMC audio will not work");
      WebViewBridge.StartFailure failure = newBridge.getStartFailure();
      newBridge.stop();
      finishFailedStartup(newBridge, failure);
      return;
    }

    boolean accepted;
    synchronized (this) {
      accepted = startingBridge == newBridge && connectionDesired;
      if (startingBridge == newBridge) {
        startingBridge = null;
      }
      if (accepted) {
        bridge = newBridge;
        lifecycle.start(sessionId, newBridge);
        lastEngineFailure = null;
        savedSessionUrl = sessionUrl;
        isActive = true;
        isConnected = false;
        hasReportedFailure = false;
        midSessionDropAttempts = 0;
        monitorStartTimeMs = System.currentTimeMillis();
        lastHealthLogMs = 0;
        createdMediaCount = 0;
        disposedMediaCount = 0;
        liveMediaCount = 0;
        recentMediaFailureCount = 0;
        lastSuccessfulPlayTimeMs = 0;
        volumeState.sessionStarted();

        ReminderHandler.getInstance().setAudioConnected(false);
        newBridge.loadUrl(sessionUrl, volumeState.preferredVolume());
        startMonitoring(sessionId);
      }
    }

    if (!accepted) {
      LOGGER.info(
          "Discarding completed helper startup because audio connection is no longer desired"
              + " (session={}, helperGeneration={})",
          sessionId,
          generation);
      newBridge.stop();
      return;
    }
    if (!newBridge.isRunning()) {
      onHelperChannelDisconnected(sessionId, newBridge);
      return;
    }

    LOGGER.info(
        "Audio session loading (session={}, helperGeneration={}, offer={})",
        sessionId,
        generation,
        describeSessionUrlForLog(sessionUrl));
  }

  private void finishFailedStartup(WebViewBridge failedBridge, WebViewBridge.StartFailure failure) {
    synchronized (this) {
      if (startingBridge != failedBridge) {
        return;
      }
      startingBridge = null;
      reportEngineFailure(failure);
      if (connectionDesired) {
        scheduleRecoveryWithBackoff("helper-startup-" + failure);
      }
    }
  }

  /** Stops the current audio session and destroys its helper/WKWebView. */
  public synchronized void disconnect() {
    connectionDesired = false;
    startingBridge = null;
    terminateSession("disconnect");
    recoveryAttempts = 0;
  }

  /** Attempts to reconnect using the last known session URL. */
  public void reconnect() {
    String reconnectUrl;
    synchronized (this) {
      reconnectUrl = savedSessionUrl;
      if (reconnectUrl == null) {
        return;
      }
      String oldSessionId = lifecycle.sessionId();
      LOGGER.info("Reconnecting audio session (session={})", oldSessionId);
      terminateSession("reconnect");
    }
    // Never inherit the service monitor across the potentially slow helper startup.
    connect(reconnectUrl);
  }

  /**
   * Lightweight check called when the app/game returns from background. Verifies the volume slider
   * still exists; if not, triggers a full reconnect.
   */
  public void softRefresh() {
    WebViewBridge targetBridge = activeConnectedBridge();
    if (targetBridge == null) {
      return;
    }
    targetBridge
        .evaluateJs(
            "(function(){ return {value: !!document.querySelector('input[type=\"range\"]')}; })()")
        .thenAccept(
            result -> {
              if (result != null && !result.optBoolean("value", true)) {
                reconnectIfCurrent(targetBridge, "Session dropped during suspension, reconnecting");
              }
            });
  }

  /** Full cleanup: stops monitoring, kills the helper process, nulls all references. */
  public synchronized void dispose() {
    connectionDesired = false;
    startingBridge = null;
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
    // This callback is routed only while automatic or manual audio connection is desired. Preserve
    // that intent across the server-owned session boundary so a fresh signed URL is always
    // requested, including when /oa connect was used while the global config switch is off.
    connectionDesired = true;
    String endedSessionId = lifecycle.sessionId();
    LOGGER.info("Server ended the audio session (session={})", endedSessionId);
    cancelAllScheduledTasks();
    lifecycle.serverEnded();
    resetSessionState();
    notifyUser("Audio session ended by server. Requesting a fresh one...");
    scheduleFreshSessionRequest(
        "server-ended", AudioRecoveryPolicy.SERVER_SESSION_RELEASE_DELAY_MS);
  }

  /**
   * Called from the client JOIN handler. A few seconds after joining, requests an audio session via
   * /audio so auto-connect no longer depends on the server's own join message arriving. Skips
   * silently if a session is already active when the delay elapses (the server's join hook won).
   */
  public synchronized void autoConnectOnJoin() {
    connectionDesired = true;
    ensureScheduler();
    if (autoConnectTask != null) {
      autoConnectTask.cancel(false);
    }
    autoConnectTask =
        scheduler.schedule(
            () -> {
              synchronized (OpenAudioMcService.this) {
                autoConnectTask = null;
                if (!connectionDesired
                    || isActive
                    || startingBridge != null
                    || sessionOfferTracker.isPending()
                    || pendingRecoveryTask != null) {
                  return;
                }
              }
              connectViaCommand();
            },
            AUTO_CONNECT_DELAY_MS,
            TimeUnit.MILLISECONDS);
  }

  /**
   * Whether config or a connection requested earlier in this Minecraft run calls for JOIN resume.
   */
  public boolean shouldConnectOnJoin(boolean configuredAutoConnect) {
    boolean shouldConnect =
        AudioRecoveryPolicy.shouldConnectOnJoin(configuredAutoConnect, connectionDesired);
    if (shouldConnect && !configuredAutoConnect) {
      LOGGER.info("Resuming requested OpenAudioMC connection after ImagineFun JOIN");
    }
    return shouldConnect;
  }

  /**
   * Called from /oa connect. Sends /audio to the server to request a fresh session URL. The
   * ChatListenerMixin will detect the URL and call connect() automatically.
   */
  public synchronized void connectViaCommand() {
    connectionDesired = true;
    if (isActive && isConnected) {
      notifyUser("Already connected to audio.");
      return;
    }
    if (isActive) {
      notifyUser("Already connecting to audio...");
      return;
    }
    if (startingBridge != null) {
      notifyUser("Already starting the audio engine...");
      return;
    }
    if (sessionOfferTracker.isPending()) {
      notifyUser("Already waiting for an audio session...");
      return;
    }

    cancelPendingRecovery();
    long requestGeneration = sessionOfferTracker.begin();
    Minecraft client = Minecraft.getInstance();
    if (client != null) {
      client.execute(
          () -> {
            if (client.player != null) {
              client.player.connection.sendCommand("audio");
            }
          });
    }

    // A visible server prompt is not enough: connect() must actually accept its signed URL. If
    // that never happens, expire this exact request and retry with the shared bounded backoff.
    ensureScheduler();
    cancelSessionOfferTimeoutTask();
    pendingSessionOfferTimeoutTask =
        scheduler.schedule(
            () -> handleSessionOfferTimeout(requestGeneration),
            SESSION_OFFER_TIMEOUT_MS,
            TimeUnit.MILLISECONDS);
  }

  /** Called from /oa disconnect. Stops the current audio session and notifies the user. */
  public synchronized void disconnectViaCommand() {
    connectionDesired = false;
    if (!isActive && pendingRecoveryTask == null && !sessionOfferTracker.isPending()) {
      cancelAllScheduledTasks();
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
    boolean canRefresh;
    synchronized (this) {
      canRefresh = savedSessionUrl != null && bridge != null && bridge.isRunning();
    }
    if (canRefresh) {
      notifyUser("Refreshing audio session...");
      CompletableFuture.runAsync(
          () -> {
            reconnect();

            // Start the fallback window only after the replacement helper has finished its cold
            // startup. This worker may wait for WebKit; the render/input thread never does.
            synchronized (OpenAudioMcService.this) {
              ensureScheduler();
              if (reconnectFallbackTask != null) {
                reconnectFallbackTask.cancel(false);
              }
              reconnectFallbackTask =
                  scheduler.schedule(
                      () -> {
                        if (!isConnected && isActive) {
                          LOGGER.info(
                              "Reconnect refresh failed after 30s, falling back to fresh /audio");
                          disconnect();
                          notifyUser("Refresh failed, requesting new session...");
                          connectViaCommand();
                        }
                      },
                      30,
                      TimeUnit.SECONDS);
            }
          });
    } else {
      notifyUser("No saved session, requesting new one...");
      connectViaCommand();
    }
  }

  /** Returns true if a /oa connect command is waiting for a session URL from the server. */
  public boolean isPendingCommandConnect() {
    return sessionOfferTracker.isPending();
  }

  /** Whether server audio lifecycle messages and offered session URLs belong to IMF. */
  public boolean shouldManageServerAudioEvents() {
    return AudioRecoveryPolicy.shouldMaintainSession(
        connectionDesired, isActive, sessionOfferTracker.isPending(), pendingRecoveryTask != null);
  }

  static boolean shouldIgnoreStartupOffer(
      boolean explicitlyRequested,
      String offeredUrl,
      String previousUrl,
      long previousOfferAtMs,
      long nowMs) {
    if (explicitlyRequested || offeredUrl == null || previousUrl == null) {
      return false;
    }
    long elapsed = nowMs - previousOfferAtMs;
    return elapsed >= 0 && elapsed < DUPLICATE_STARTUP_OFFER_WINDOW_MS;
  }

  public boolean isConnected() {
    return isConnected;
  }

  public boolean isActive() {
    return isActive;
  }

  /** Returns the current volume (0-100), or -1 if unknown. */
  public int getCurrentVolume() {
    return currentVolume;
  }

  /** Records a successful server-side /volume command and applies it to the current helper. */
  public synchronized void onServerVolumeChanged(int volume) {
    if (!AudioVolumeState.isValid(volume)) {
      return;
    }
    currentVolume = volume;
    volumeStore.save(volume);
    WebViewBridge targetBridge = bridge;
    String sessionId = lifecycle.sessionId();
    if (targetBridge == null || !targetBridge.isRunning() || !isActive || sessionId == null) {
      volumeState.recordExplicitVolume(volume);
      return;
    }
    volumeState.recordExternalVolume(volume);
    LOGGER.info("Applying server-confirmed OpenAudioMC volume: {}%", volume);
    restorePreferredVolume(sessionId, volume);
  }

  /** Monotonically increasing helper generation, including planned and crash recovery. */
  public long getHelperGeneration() {
    return helperGeneration;
  }

  public int getLiveMediaCount() {
    return liveMediaCount;
  }

  public long getLastSuccessfulPlayTimeMs() {
    return lastSuccessfulPlayTimeMs;
  }

  /**
   * Sets the volume silently (no chat notification). Used by the options screen slider, which fires
   * continuously while dragging.
   */
  public void setVolumeFromSlider(int volume) {
    WebViewBridge targetBridge = activeConnectedBridge();
    if (volume < 0 || volume > 100 || targetBridge == null) {
      return;
    }
    rememberPreferredVolume(volume);
    String js = String.format(SET_VOLUME_JS_TEMPLATE, volume);
    targetBridge
        .evaluateJs(js)
        .thenAccept(
            result -> {
              if (result != null && result.optBoolean("success", false)) {
                int actualVolume = result.optInt("value", volume);
                synchronized (OpenAudioMcService.this) {
                  if (bridge != targetBridge || !isActive || !isConnected) {
                    return;
                  }
                  currentVolume = actualVolume;
                }
                rememberPreferredVolume(actualVolume);
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
    WebViewBridge targetBridge = activeConnectedBridge();
    if (targetBridge == null) {
      notifyUser("Cannot set volume: not connected to audio.");
      return;
    }
    rememberPreferredVolume(volume);
    String js = String.format(SET_VOLUME_JS_TEMPLATE, volume);
    targetBridge
        .evaluateJs(js)
        .thenAccept(
            result -> {
              if (result != null && result.optBoolean("success", false)) {
                int actualVolume = result.optInt("value", volume);
                synchronized (OpenAudioMcService.this) {
                  if (bridge != targetBridge || !isActive || !isConnected) {
                    return;
                  }
                  currentVolume = actualVolume;
                }
                rememberPreferredVolume(actualVolume);
                notifyUser("Volume set to " + actualVolume + "%");
              } else {
                synchronized (OpenAudioMcService.this) {
                  if (bridge != targetBridge || !isActive || !isConnected) {
                    return;
                  }
                }
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

    if (handleMediaHealth(sessionId, result.optJSONObject("mediaHealth"))) {
      return;
    }

    if (hasRangeInput) {
      // Track volume from the range input
      int pageVolume = result.optInt("rangeValue", -1);
      AudioVolumeState.Observation volumeObservation = volumeState.observePageVolume(pageVolume);
      int volume = volumeObservation.effectiveVolume();
      if (volume >= 0) {
        currentVolume = volume;
      }
      if (volumeObservation.persistenceRequired()) {
        volumeStore.save(volume);
        LOGGER.info("Persisted OpenAudioMC page volume change: {}%", volume);
      }
      if (volumeObservation.restoreRequired()) {
        restorePreferredVolume(sessionId, volume);
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
      LOGGER.debug("Auto-clicking 'Start Audio Session' button");
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
          "Audio session dropped; destroying old helper before recovery (session={},"
              + " attempt={}/{})",
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
    scheduleRecoveryWithBackoff("connection-" + reason);
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

  private void restorePreferredVolume(String sessionId, int volume) {
    WebViewBridge targetBridge;
    synchronized (this) {
      targetBridge = bridge;
    }
    if (targetBridge == null || !targetBridge.isRunning() || volume < 0) {
      volumeState.restoreCompleted(false, AudioVolumeState.UNKNOWN);
      return;
    }
    LOGGER.info(
        "Restoring OpenAudioMC volume across helper generation (session={}, helperGeneration={},"
            + " volume={})",
        sessionId,
        helperGeneration,
        volume);
    targetBridge
        .evaluateJs(String.format(SET_VOLUME_JS_TEMPLATE, volume))
        .whenComplete(
            (result, failure) -> {
              synchronized (OpenAudioMcService.this) {
                if (!sessionId.equals(lifecycle.sessionId()) || bridge != targetBridge) {
                  return;
                }
                boolean success =
                    failure == null && result != null && result.optBoolean("success", false);
                int actualVolume =
                    success ? result.optInt("value", volume) : AudioVolumeState.UNKNOWN;
                volumeState.restoreCompleted(success, actualVolume);
                if (success) {
                  currentVolume = actualVolume;
                  volumeStore.save(actualVolume);
                  LOGGER.info(
                      "Restored OpenAudioMC volume (session={}, helperGeneration={}, volume={})",
                      sessionId,
                      helperGeneration,
                      actualVolume);
                } else {
                  LOGGER.warn(
                      "Failed to restore OpenAudioMC volume; will retry on next health poll"
                          + " (session={}, helperGeneration={}, volume={})",
                      sessionId,
                      helperGeneration,
                      volume,
                      failure);
                }
              }
            });
  }

  private synchronized void onHelperEngineFailure(
      String sessionId, WebViewBridge failedBridge, String reason) {
    if (!sessionId.equals(lifecycle.sessionId()) || bridge != failedBridge) {
      return;
    }
    LOGGER.warn(
        "Audio helper engine failed; recycling complete helper (session={}, helperGeneration={},"
            + " reason={})",
        sessionId,
        helperGeneration,
        reason);
    cancelAllScheduledTasks();
    lifecycle.helperEngineFailed(reason);
    resetSessionState();
    scheduleRecoveryWithBackoff(reason);
  }

  private boolean handleMediaHealth(String sessionId, JSONObject healthJson) {
    if (healthJson == null) {
      return false;
    }
    AudioHelperHealthPolicy.MediaHealth health =
        new AudioHelperHealthPolicy.MediaHealth(
            healthJson.optInt("created", 0),
            healthJson.optInt("disposed", 0),
            healthJson.optInt("live", 0),
            healthJson.optLong("lastSuccessfulPlayAt", 0),
            healthJson.optLong("lastEndedAt", 0),
            healthJson.optInt("recentErrorAbort", 0),
            healthJson.optInt("totalErrorAbort", 0));
    createdMediaCount = health.created();
    disposedMediaCount = health.disposed();
    liveMediaCount = health.live();
    recentMediaFailureCount = health.recentErrorAbort();
    lastSuccessfulPlayTimeMs = health.lastSuccessfulPlayAt();
    JSONObject audioContexts = healthJson.optJSONObject("audioContexts");
    int contextsLive = audioContexts != null ? audioContexts.optInt("live", 0) : 0;
    int contextsRunning = audioContexts != null ? audioContexts.optInt("running", 0) : 0;
    int contextsSuspended = audioContexts != null ? audioContexts.optInt("suspended", 0) : 0;
    int contextsInterrupted = audioContexts != null ? audioContexts.optInt("interrupted", 0) : 0;
    int contextResumeAttempts =
        audioContexts != null ? audioContexts.optInt("resumeAttempts", 0) : 0;
    int playAttempts = healthJson.optInt("playAttempts", 0);
    int playResolved = healthJson.optInt("playResolved", 0);
    int playRejected = healthJson.optInt("playRejected", 0);
    int playPending = healthJson.optInt("playPending", 0);
    int stalePlayRejected = healthJson.optInt("stalePlayRejected", 0);
    int graphsLive = healthJson.optInt("graphsLive", 0);
    int muteSpeakersLive = healthJson.optInt("muteSpeakersLive", 0);
    int muteRegionsLive = healthJson.optInt("muteRegionsLive", 0);
    int muteSpeakersSuppressed = healthJson.optInt("muteSpeakersSuppressed", 0);
    long lastMuteSpeakersSuppressedAt = healthJson.optLong("lastMuteSpeakersSuppressedAt", 0);
    int associatedLive = healthJson.optInt("associatedLive", 0);
    int zeroVolumeLive = healthJson.optInt("zeroVolumeLive", 0);
    int mutedLive = healthJson.optInt("mutedLive", 0);
    int playingLive = healthJson.optInt("playingLive", 0);
    int playingSilentLive = healthJson.optInt("playingSilentLive", 0);
    int masterVolume = healthJson.optInt("masterVolume", -1);
    boolean volumeGateActive = healthJson.optBoolean("volumeGateActive", false);
    int volumeRestored = healthJson.optInt("volumeRestored", 0);
    long lastVolumeRestoreAt = healthJson.optLong("lastVolumeRestoreAt", 0);
    String injectionMode = healthJson.optString("mode", "managed-lifecycle");
    int observeEvents = healthJson.optInt("observeEvents", 0);
    int observeDropped = healthJson.optInt("observeDropped", 0);
    long observeLastSequence = healthJson.optLong("observeLastSequence", 0);
    int observedMedia = healthJson.optInt("observedMedia", 0);
    int observedSourceNodes = healthJson.optInt("observedSourceNodes", 0);
    int staleCandidates = healthJson.optInt("staleCandidates", 0);
    int staleBlocked = healthJson.optInt("staleBlocked", 0);
    int staleDisposed = healthJson.optInt("staleDisposed", 0);
    int nativePlayForwarded = healthJson.optInt("nativePlayForwarded", 0);
    int allowedFirstPlays = healthJson.optInt("allowedFirstPlays", 0);
    int guardLiveCandidates = healthJson.optInt("guardLiveCandidates", 0);
    int guardPendingTimers = healthJson.optInt("guardPendingTimers", 0);
    long lastBlockedAt = healthJson.optLong("lastBlockedAt", 0);
    String lastBlockedSourceHash = healthJson.optString("lastBlockedSourceHash", "-");
    int lastBlockedPauseAgeMs = healthJson.optInt("lastBlockedPauseAgeMs", -1);

    long now = System.currentTimeMillis();
    long sessionAgeMs = now - monitorStartTimeMs;
    if (LOGGER.isDebugEnabled() && (lastHealthLogMs == 0 || now - lastHealthLogMs >= 60_000)) {
      lastHealthLogMs = now;
      LOGGER.debug(
          "Audio helper health (session={}, helperGeneration={}, injectionMode={}, ageMs={},"
              + " created={}, disposed={},"
              + " live={}, recentErrorAbort={}, lastSuccessfulPlayAt={}, lastEndedAt={},"
              + " contextsLive={}, contextsRunning={}, contextsSuspended={},"
              + " contextsInterrupted={}, contextResumeAttempts={}, playAttempts={},"
              + " playResolved={}, playRejected={}, playPending={}, stalePlayRejected={},"
              + " graphsLive={}, muteSpeakersLive={}, muteRegionsLive={},"
              + " muteSpeakersSuppressed={}, lastMuteSpeakersSuppressedAt={},"
              + " associatedLive={}, zeroVolumeLive={}, mutedLive={}, playingLive={},"
              + " playingSilentLive={}, masterVolume={}, volumeGateActive={},"
              + " volumeRestored={}, lastVolumeRestoreAt={})",
          sessionId,
          helperGeneration,
          injectionMode,
          sessionAgeMs,
          health.created(),
          health.disposed(),
          health.live(),
          health.recentErrorAbort(),
          health.lastSuccessfulPlayAt(),
          health.lastEndedAt(),
          contextsLive,
          contextsRunning,
          contextsSuspended,
          contextsInterrupted,
          contextResumeAttempts,
          playAttempts,
          playResolved,
          playRejected,
          playPending,
          stalePlayRejected,
          graphsLive,
          muteSpeakersLive,
          muteRegionsLive,
          muteSpeakersSuppressed,
          lastMuteSpeakersSuppressedAt,
          associatedLive,
          zeroVolumeLive,
          mutedLive,
          playingLive,
          playingSilentLive,
          masterVolume,
          volumeGateActive,
          volumeRestored,
          lastVolumeRestoreAt);
      if ("legacy-observe".equals(injectionMode)) {
        LOGGER.debug(
            "Legacy audio observation health (session={}, helperGeneration={}, events={},"
                + " dropped={}, lastSequence={}, observedMedia={}, observedSourceNodes={})",
            sessionId,
            helperGeneration,
            observeEvents,
            observeDropped,
            observeLastSequence,
            observedMedia,
            observedSourceNodes);
      } else if ("legacy-guarded".equals(injectionMode)) {
        LOGGER.debug(
            "Legacy audio guard health (session={}, helperGeneration={}, candidates={},"
                + " blocked={}, disposed={}, nativePlayForwarded={}, allowedFirstPlays={},"
                + " liveCandidates={}, pendingTimers={}, lastBlockedAt={},"
                + " lastBlockedSourceHash={}, lastBlockedPauseAgeMs={})",
            sessionId,
            helperGeneration,
            staleCandidates,
            staleBlocked,
            staleDisposed,
            nativePlayForwarded,
            allowedFirstPlays,
            guardLiveCandidates,
            guardPendingTimers,
            lastBlockedAt,
            lastBlockedSourceHash,
            lastBlockedPauseAgeMs);
      }
    }

    String recycleReason = AudioHelperHealthPolicy.recycleReason(health);
    if (recycleReason == null) {
      return false;
    }
    LOGGER.warn(
        "Audio helper exceeded bounded health policy; rotating entire helper (session={},"
            + " helperGeneration={}, reason={}, created={}, disposed={}, live={},"
            + " recentErrorAbort={})",
        sessionId,
        helperGeneration,
        recycleReason,
        health.created(),
        health.disposed(),
        health.live(),
        health.recentErrorAbort());
    terminateSession("bounded-recycle-" + recycleReason);
    recoveryAttempts = 0;
    notifyUser("Audio engine maintenance recycle; requesting a fresh session...");
    scheduleFreshSessionRequest(
        "bounded-recycle-" + recycleReason, AudioRecoveryPolicy.SERVER_SESSION_RELEASE_DELAY_MS);
    return true;
  }

  /**
   * Called when leaving any multiplayer server. Session resources are always destroyed, while a
   * connection requested during this Minecraft run remains desired for the next ImagineFun JOIN.
   */
  public synchronized void onLeaveServer() {
    // A helper whose cold start finishes after this point belongs to the departed connection. The
    // identity check in connect() will reject and close it without erasing the preserved intent.
    startingBridge = null;
    cancelAllScheduledTasks();
    lifecycle.leaveServer();
    resetSessionState();
    recoveryAttempts = 0;
    LOGGER.info(
        "Audio server-leave cleanup complete (resumeOnNextImagineFunJoin={})", connectionDesired);
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
    currentVolume = volumeState.preferredVolume();
    volumeState.sessionStopped();
    sessionOfferTracker.cancel();
    ReminderHandler.getInstance().setAudioConnected(false);
  }

  private void rememberPreferredVolume(int volume) {
    volumeState.recordExplicitVolume(volume);
    currentVolume = volume;
    volumeStore.save(volume);
  }

  private synchronized WebViewBridge activeConnectedBridge() {
    WebViewBridge targetBridge = bridge;
    if (targetBridge == null || !targetBridge.isRunning() || !isActive || !isConnected) {
      return null;
    }
    return targetBridge;
  }

  private void reconnectIfCurrent(WebViewBridge expectedBridge, String reason) {
    String reconnectUrl;
    synchronized (this) {
      if (bridge != expectedBridge || !isActive || !isConnected || savedSessionUrl == null) {
        return;
      }
      reconnectUrl = savedSessionUrl;
      LOGGER.info(reason);
      terminateSession("soft-refresh");
    }
    connect(reconnectUrl);
  }

  private void scheduleRecoveryWithBackoff(String reason) {
    AudioRecoveryPolicy.RetryDecision decision =
        AudioRecoveryPolicy.afterFailure(
            recoveryAttempts, MAX_RECOVERY_ATTEMPTS, MONITOR_INTERVAL_MS);
    if (!decision.retry()) {
      LOGGER.error(
          "Audio recovery limit reached; leaving helper stopped (reason={}, attempts={})",
          reason,
          recoveryAttempts);
      notifyUser("Audio recovery stopped after repeated failures. Use /oa connect to try again.");
      return;
    }
    recoveryAttempts = decision.nextAttempt();
    scheduleFreshSessionRequest(reason, decision.delayMs());
  }

  private synchronized void handleSessionOfferTimeout(long requestGeneration) {
    if (!sessionOfferTracker.expire(requestGeneration)) {
      return;
    }
    pendingSessionOfferTimeoutTask = null;
    if (!AudioRecoveryPolicy.shouldRetryMissingSessionOffer(
        connectionDesired, isActive, startingBridge != null)) {
      return;
    }
    LOGGER.warn(
        "OpenAudioMC session offer timed out before a usable URL was accepted"
            + " (requestGeneration={}, recoveryAttempts={})",
        requestGeneration,
        recoveryAttempts);
    scheduleRecoveryWithBackoff("session-offer-timeout");
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
                if (!connectionDesired) {
                  LOGGER.info(
                      "Skipping fresh audio session because connection is no longer desired"
                          + " (reason={})",
                      reason);
                  return;
                }
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

  private void cancelSessionOfferTimeoutTask() {
    if (pendingSessionOfferTimeoutTask != null) {
      pendingSessionOfferTimeoutTask.cancel(false);
      pendingSessionOfferTimeoutTask = null;
    }
  }

  private void cancelAllScheduledTasks() {
    cancelPendingRecovery();
    if (autoConnectTask != null) {
      autoConnectTask.cancel(false);
      autoConnectTask = null;
    }
    cancelSessionOfferTimeoutTask();
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
