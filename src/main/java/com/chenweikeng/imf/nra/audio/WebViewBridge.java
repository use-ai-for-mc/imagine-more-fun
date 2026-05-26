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

  /** Directory (under configDir) where we extract/cache native WebView helpers. */
  private static Path helperDir() {
    return ImfStorage.nativeHelperDir();
  }

  private Process process;
  private BufferedWriter writer;
  private Thread readerThread;
  private volatile boolean running;
  private final Map<String, CompletableFuture<JSONObject>> pendingEvals = new ConcurrentHashMap<>();
  private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();

  public boolean start() {
    Path helperPath = findHelperBinary();
    if (helperPath == null) {
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
        LOGGER.error("WebView helper did not become ready within 15 seconds", e);
        stop();
        return false;
      }

      LOGGER.info("WebView helper process started (pid={})", process.pid());
      return true;
    } catch (IOException e) {
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
        LOGGER.warn("WebView helper error: {}", response.optString("message", ""));
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

    if (isWin && !isWindowsDesktopRuntimeAvailable()) {
      LOGGER.error(
          "WebView helper requires .NET 8 Desktop Runtime on Windows."
              + " Download from: https://dotnet.microsoft.com/download/dotnet/8.0");
      return null;
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
  private boolean isWindowsDesktopRuntimeAvailable() {
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
}
