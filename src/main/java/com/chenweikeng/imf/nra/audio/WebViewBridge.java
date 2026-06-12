package com.chenweikeng.imf.nra.audio;

import com.chenweikeng.imf.ImfStorage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a native webview helper process and communicates with it over stdin/stdout using
 * newline-delimited JSON.
 *
 * <p>Protocol - commands (Java → helper stdin):
 *
 * <pre>
 *   {"cmd":"load","url":"https://..."}
 *   {"cmd":"eval","js":"...","id":"uuid"}
 *   {"cmd":"quit"}
 * </pre>
 *
 * Responses (helper stdout → Java):
 *
 * <pre>
 *   {"type":"ready"}
 *   {"type":"loaded","url":"..."}
 *   {"type":"eval_result","id":"uuid","result":{...}}
 *   {"type":"error","message":"..."}
 * </pre>
 */
public class WebViewBridge {
  private static final Logger LOGGER = LoggerFactory.getLogger("WebViewBridge");
  private static final long EVAL_TIMEOUT_SECONDS = 10;

  /**
   * Why {@link #start()} failed, with user-facing guidance for the causes the player can fix
   * themselves (missing runtimes). The two runtime cases are detectable without spawning anything
   * via {@link #preflightCheck()}.
   */
  public enum StartFailure {
    // Both URLs are Microsoft-maintained permalinks that serve the installer directly (no
    // version-picker page): aka.ms tracks the latest 8.0.x Desktop Runtime x64 exe, fwlink
    // serves the WebView2 Evergreen bootstrapper which auto-detects the architecture.
    DOTNET_RUNTIME_MISSING(
        "Audio engine needs the .NET 8 Desktop Runtime. Click to download the installer, run"
            + " it, then restart Minecraft: ",
        "https://aka.ms/dotnet/8.0/windowsdesktop-runtime-win-x64.exe"),
    WEBVIEW2_RUNTIME_MISSING(
        "Audio engine needs the Microsoft Edge WebView2 Runtime. Click to download the"
            + " installer, run it, then restart Minecraft: ",
        "https://go.microsoft.com/fwlink/p/?LinkId=2124703"),
    HELPER_BINARY_MISSING(
        "Audio engine helper is missing from this build of the mod. Re-download the official"
            + " release JAR.",
        null),
    HELPER_LAUNCH_FAILED("Audio engine helper failed to launch. See logs/latest.log.", null),
    HELPER_NOT_READY("Audio engine helper did not respond in time. See logs/latest.log.", null);

    private final String userMessage;
    private final String helpUrl;

    StartFailure(String userMessage, String helpUrl) {
      this.userMessage = userMessage;
      this.helpUrl = helpUrl;
    }

    public String userMessage() {
      return userMessage;
    }

    /** Download URL the user needs, or null when there is nothing for them to install. */
    public String helpUrl() {
      return helpUrl;
    }
  }

  /** Directory (under configDir) where we extract/cache native WebView helpers. */
  private static Path helperDir() {
    return ImfStorage.nativeHelperDir();
  }

  private Process process;
  private BufferedWriter writer;
  private Thread readerThread;
  private volatile boolean running;
  private volatile StartFailure startFailure;
  private final Map<String, CompletableFuture<JSONObject>> pendingEvals = new ConcurrentHashMap<>();
  private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();

  /**
   * Cheap, spawn-free check for the runtimes the Windows helper needs. Returns the failure that
   * would make {@link #start()} fail, or null if startup can be attempted. Lets the caller fail
   * fast (and quietly, on repeats) instead of spawning a helper that dies and timing out 15 seconds
   * later on every retry.
   */
  public static StartFailure preflightCheck() {
    String os = System.getProperty("os.name", "").toLowerCase();
    if (!os.contains("win")) {
      return null;
    }
    if (!isWindowsDesktopRuntimeAvailable()) {
      return StartFailure.DOTNET_RUNTIME_MISSING;
    }
    if (!isWebView2RuntimeAvailable()) {
      return StartFailure.WEBVIEW2_RUNTIME_MISSING;
    }
    return null;
  }

  /** Why the last {@link #start()} call failed, or null if it succeeded / was never called. */
  public StartFailure getStartFailure() {
    return startFailure;
  }

  public boolean start() {
    StartFailure preflight = preflightCheck();
    if (preflight != null) {
      startFailure = preflight;
      return false;
    }

    Path helperPath = findHelperBinary();
    if (helperPath == null) {
      startFailure = StartFailure.HELPER_BINARY_MISSING;
      LOGGER.error(
          "WebView helper binary not found. Place it at: {}/webview-helper (macOS) or"
              + " {}/webview-helper.exe (Windows)",
          helperDir(),
          helperDir());
      return false;
    }

    try {
      ProcessBuilder pb = new ProcessBuilder(helperPath.toAbsolutePath().toString());
      pb.redirectErrorStream(false);
      process = pb.start();

      // Fail the ready-wait immediately if the helper dies (e.g. it printed an error and
      // exited) instead of sitting out the full 15-second timeout.
      process
          .onExit()
          .thenAccept(
              p -> {
                if (!readyFuture.isDone()) {
                  readyFuture.completeExceptionally(
                      new IOException("helper exited with code " + p.exitValue()));
                }
              });

      writer =
          new BufferedWriter(
              new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

      running = true;
      readerThread = new Thread(this::readLoop, "WebViewBridge-Reader");
      readerThread.setDaemon(true);
      readerThread.start();

      // Wait for the helper to signal ready
      try {
        readyFuture.get(15, TimeUnit.SECONDS);
      } catch (Exception e) {
        if (startFailure == null) {
          startFailure = StartFailure.HELPER_NOT_READY;
        }
        LOGGER.error("WebView helper did not become ready ({}): {}", startFailure, e.toString());
        stop();
        return false;
      }

      LOGGER.info("WebView helper process started (pid={})", process.pid());
      return true;
    } catch (IOException e) {
      startFailure = StartFailure.HELPER_LAUNCH_FAILED;
      LOGGER.error("Failed to start WebView helper process", e);
      return false;
    }
  }

  public void stop() {
    running = false;
    try {
      sendCommand(new JSONObject().put("cmd", "quit"));
    } catch (Exception e) {
      // Best effort
    }
    if (process != null) {
      try {
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          process.destroyForcibly();
        }
      } catch (InterruptedException e) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
      }
      process = null;
    }
    writer = null;

    // Fail all pending evals
    for (var entry : pendingEvals.entrySet()) {
      entry.getValue().completeExceptionally(new IOException("WebView bridge stopped"));
    }
    pendingEvals.clear();
  }

  public void loadUrl(String url) {
    sendCommand(new JSONObject().put("cmd", "load").put("url", url));
  }

  public CompletableFuture<JSONObject> evaluateJs(String js) {
    String id = UUID.randomUUID().toString();
    CompletableFuture<JSONObject> future = new CompletableFuture<>();
    pendingEvals.put(id, future);

    sendCommand(new JSONObject().put("cmd", "eval").put("js", js).put("id", id));

    // Auto-timeout to prevent leaks
    future
        .orTimeout(EVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .whenComplete(
            (result, ex) -> {
              pendingEvals.remove(id);
            });

    return future;
  }

  public boolean isRunning() {
    return running && process != null && process.isAlive();
  }

  private void sendCommand(JSONObject command) {
    if (writer == null || !isRunning()) {
      return;
    }
    try {
      synchronized (this) {
        writer.write(command.toString());
        writer.newLine();
        writer.flush();
      }
    } catch (IOException e) {
      LOGGER.error("Failed to send command to WebView helper", e);
    }
  }

  private void readLoop() {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while (running && (line = reader.readLine()) != null) {
        try {
          handleResponse(new JSONObject(line));
        } catch (Exception e) {
          LOGGER.warn("Failed to parse helper response: {}", line, e);
        }
      }
    } catch (IOException e) {
      if (running) {
        LOGGER.error("WebView helper stdout read error", e);
      }
    }
    running = false;

    // Fail all pending evals
    for (var entry : pendingEvals.entrySet()) {
      entry.getValue().completeExceptionally(new IOException("WebView helper process ended"));
    }
    pendingEvals.clear();
  }

  private void handleResponse(JSONObject response) {
    String type = response.optString("type", "");
    switch (type) {
      case "ready":
        LOGGER.info("WebView helper is ready");
        readyFuture.complete(null);
        break;
      case "eval_result":
        String id = response.optString("id", "");
        CompletableFuture<JSONObject> future = pendingEvals.remove(id);
        if (future != null) {
          JSONObject result = response.optJSONObject("result");
          future.complete(result != null ? result : new JSONObject());
        }
        break;
      case "loaded":
        LOGGER.debug("Page loaded: {}", response.optString("url", ""));
        break;
      case "console":
        String level = response.optString("level", "log");
        String msg = response.optString("message", "");
        if ("error".equals(level) || "uncaught".equals(level) || "rejection".equals(level)) {
          LOGGER.warn("[JS {}] {}", level, msg);
        } else if ("warn".equals(level)) {
          LOGGER.debug("[JS {}] {}", level, msg);
        }
        break;
      case "error":
        String errorMessage = response.optString("message", "");
        LOGGER.warn("WebView helper error: {}", errorMessage);
        if (!readyFuture.isDone()) {
          // Startup error (the helper reports its WebView2 init exception this way and then
          // exits). Classify it so the user gets actionable guidance, and fail the ready-wait
          // now rather than letting it time out.
          if (errorMessage.toLowerCase().contains("webview2")) {
            startFailure = StartFailure.WEBVIEW2_RUNTIME_MISSING;
          }
          readyFuture.completeExceptionally(new IOException(errorMessage));
        }
        break;
      case "web_content_terminated":
        LOGGER.warn("WebKit content process terminated (WebView audio engine crashed)");
        break;
      default:
        LOGGER.debug("Unknown helper response type: {}", type);
    }
  }

  private Path findHelperBinary() {
    String os = System.getProperty("os.name", "").toLowerCase();
    boolean isMac = os.contains("mac") || os.contains("darwin");
    boolean isWin = os.contains("win");

    if (!isMac && !isWin) {
      LOGGER.error("Unsupported OS for WebView helper: {}", os);
      return null;
    }

    String binaryName = isMac ? "webview-helper" : "webview-helper.exe";
    Path dir = helperDir();

    // Check alongside the running game JAR first — explicit override for dev setups.
    Path gameDirPath = Path.of(binaryName);
    if (Files.isExecutable(gameDirPath)) {
      LOGGER.debug("Using existing WebView helper at: {}", gameDirPath);
      return gameDirPath;
    }

    // Extract from the JAR; NativeHelperExtractor compares the JAR resource hash to a
    // sidecar of the cached copy and re-extracts on mismatch — so a stale cached binary
    // from a previous mod version cannot shadow the one we just shipped.
    String resourcePath = "/native/" + (isMac ? "macos/" : "windows/") + binaryName;
    Path extracted =
        com.chenweikeng.imf.NativeHelperExtractor.findOrExtract(
            WebViewBridge.class, resourcePath, dir.resolve(binaryName), true);
    if (extracted != null && isWin) {
      com.chenweikeng.imf.NativeHelperExtractor.findOrExtract(
          WebViewBridge.class,
          "/native/windows/WebView2Loader.dll",
          dir.resolve("WebView2Loader.dll"),
          false);
    }
    return extracted;
  }

  /**
   * Checks if the .NET 8 Desktop Runtime is available on Windows by looking for the shared runtime
   * directory. Without it, the framework-dependent webview-helper.exe cannot run.
   */
  private static boolean isWindowsDesktopRuntimeAvailable() {
    String programFiles = System.getenv("ProgramFiles");
    if (programFiles == null) {
      programFiles = "C:\\Program Files";
    }
    Path runtimeDir = Path.of(programFiles, "dotnet", "shared", "Microsoft.WindowsDesktop.App");
    if (!Files.isDirectory(runtimeDir)) {
      return false;
    }
    try (var entries = Files.list(runtimeDir)) {
      return entries.anyMatch(
          p -> {
            String name = p.getFileName().toString();
            int dot = name.indexOf('.');
            if (dot < 0) {
              return false; // not a versioned directory (e.g. lock file)
            }
            try {
              int major = Integer.parseInt(name.substring(0, dot));
              return major >= 8;
            } catch (NumberFormatException e) {
              return false;
            }
          });
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Checks if the Microsoft Edge WebView2 Runtime is installed. Without it the helper's
   * CoreWebView2Environment.CreateAsync throws ("Couldn't find a compatible WebView2 Runtime
   * installation") and the helper exits. Ships in-box on Windows 11 but is genuinely absent on some
   * Windows 10 machines (never updated, Enterprise/LTSC/N editions, Edge-debloated).
   *
   * <p>Checks the Evergreen install directories (per-machine and per-user), then falls back to the
   * EdgeUpdate registry entries Microsoft documents as the canonical detection method.
   */
  private static boolean isWebView2RuntimeAvailable() {
    String[] roots = {
      System.getenv("ProgramFiles(x86)"),
      System.getenv("ProgramFiles"),
      System.getenv("LOCALAPPDATA")
    };
    for (String root : roots) {
      if (root == null) {
        continue;
      }
      if (hasVersionedSubdir(Path.of(root, "Microsoft", "EdgeWebView", "Application"))) {
        return true;
      }
    }
    return isWebView2RegisteredInRegistry();
  }

  private static boolean hasVersionedSubdir(Path dir) {
    if (!Files.isDirectory(dir)) {
      return false;
    }
    try (var entries = Files.list(dir)) {
      return entries.anyMatch(
          p -> {
            String name = p.getFileName().toString();
            return !name.isEmpty() && Character.isDigit(name.charAt(0)) && Files.isDirectory(p);
          });
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean isWebView2RegisteredInRegistry() {
    // WebView2 Runtime's EdgeUpdate client GUID, per Microsoft's distribution docs. A pv value
    // of 0.0.0.0 means a broken/uninstalled state.
    String guid = "{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}";
    String[] keys = {
      "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\EdgeUpdate\\Clients\\" + guid,
      "HKLM\\SOFTWARE\\Microsoft\\EdgeUpdate\\Clients\\" + guid,
      "HKCU\\Software\\Microsoft\\EdgeUpdate\\Clients\\" + guid,
    };
    for (String key : keys) {
      Process reg = null;
      try {
        reg = new ProcessBuilder("reg", "query", key, "/v", "pv").redirectErrorStream(true).start();
        String out = new String(reg.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!reg.waitFor(5, TimeUnit.SECONDS)) {
          reg.destroyForcibly();
          continue;
        }
        if (reg.exitValue() == 0 && out.contains("pv") && !out.contains("0.0.0.0")) {
          return true;
        }
      } catch (IOException e) {
        // reg.exe unavailable or query failed; try the next hive
      } catch (InterruptedException e) {
        if (reg != null) {
          reg.destroyForcibly();
        }
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }
}
