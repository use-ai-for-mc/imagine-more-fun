package com.chenweikeng.imf.nra.status;

import com.chenweikeng.imf.ImfStorage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the native status helper process (macOS menu bar item / Windows notification-area icon)
 * and sends short text updates over stdin. Use to show a glanceable countdown or status while the
 * Minecraft window is minimized.
 *
 * <p>Protocol - commands (Java → helper stdin):
 *
 * <pre>
 *   {"cmd":"set","text":"2:45"}
 *   {"cmd":"quit"}
 * </pre>
 *
 * Responses (helper stdout → Java):
 *
 * <pre>
 *   {"type":"ready"}
 *   {"type":"error","message":"..."}
 * </pre>
 */
public class StatusBarBridge {
  private static final Logger LOGGER = LoggerFactory.getLogger("StatusBarBridge");
  private static final long READY_TIMEOUT_SECONDS = 5;

  private Process process;
  private BufferedWriter writer;
  private Thread readerThread;
  private volatile boolean running;
  private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();

  public boolean start() {
    Path helperPath = findHelperBinary();
    if (helperPath == null) {
      LOGGER.warn("Status helper binary not found; menu bar / tray countdown disabled");
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
      readerThread = new Thread(this::readLoop, "StatusBarBridge-Reader");
      readerThread.setDaemon(true);
      readerThread.start();

      try {
        readyFuture.get(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (Exception e) {
        LOGGER.error("Status helper did not become ready within {}s", READY_TIMEOUT_SECONDS, e);
        stop();
        return false;
      }

      LOGGER.info("Status helper started (pid={})", process.pid());
      return true;
    } catch (IOException e) {
      LOGGER.error("Failed to start status helper process", e);
      return false;
    }
  }

  public void setText(String text) {
    sendCommand(new JSONObject().put("cmd", "set").put("text", text));
  }

  public void stop() {
    running = false;
    try {
      sendCommand(new JSONObject().put("cmd", "quit"));
    } catch (Exception ignore) {
      // Best effort.
    }
    if (process != null) {
      try {
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
          process.destroyForcibly();
        }
      } catch (InterruptedException e) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
      }
      process = null;
    }
    writer = null;
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
      LOGGER.warn("Failed to send command to status helper: {}", e.getMessage());
    }
  }

  private void readLoop() {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while (running && (line = reader.readLine()) != null) {
        try {
          JSONObject json = new JSONObject(line);
          String type = json.optString("type", "");
          switch (type) {
            case "ready" -> readyFuture.complete(null);
            case "error" -> LOGGER.warn("Status helper error: {}", json.optString("message", ""));
            default -> {
              // ignored
            }
          }
        } catch (Exception e) {
          LOGGER.debug("Unparseable status helper output: {}", line);
        }
      }
    } catch (IOException e) {
      if (running) {
        LOGGER.warn("Status helper stdout read error: {}", e.getMessage());
      }
    }
    running = false;
  }

  private Path findHelperBinary() {
    String os = System.getProperty("os.name", "").toLowerCase();
    boolean isMac = os.contains("mac") || os.contains("darwin");
    boolean isWin = os.contains("win");
    if (!isMac && !isWin) {
      return null;
    }

    String binaryName = isMac ? "status-helper" : "status-helper.exe";
    Path dir = ImfStorage.nativeHelperDir();
    // Hash-checked extraction: see NativeHelperExtractor — re-extracts on mod-version mismatch.
    return com.chenweikeng.imf.NativeHelperExtractor.findOrExtract(
        StatusBarBridge.class,
        "/native/" + (isMac ? "macos/" : "windows/") + binaryName,
        dir.resolve(binaryName),
        true);
  }
}
