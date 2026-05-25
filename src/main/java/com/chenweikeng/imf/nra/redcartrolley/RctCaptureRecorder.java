package com.chenweikeng.imf.nra.redcartrolley;

import com.chenweikeng.imf.ImfChat;
import com.chenweikeng.imf.ImfStorage;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.redcartrolley.RctTrains.Car;
import com.chenweikeng.imf.nra.redcartrolley.RctTrains.Cluster;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.chenweikeng.imf.nra.ride.RideName;
import com.google.gson.Gson;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;

/**
 * Command-driven logger for Red Car Trolley movement. While a capture session is running it samples
 * every detected train (see {@link RctTrains}) at a fixed cadence and appends one JSON object per
 * line (NDJSON) to {@code <config>/imaginemorefun/rct-captures/}. Each train carries a per-session
 * track id assigned by nearest-match continuity so a single train's path is one track across the
 * file. The data is intentionally raw and complete — route/prediction modelling happens later.
 *
 * <p>Driven by {@code /imf rct capture start|stop|status|mark <label>}.
 */
public final class RctCaptureRecorder {
  private static final RctCaptureRecorder INSTANCE = new RctCaptureRecorder();

  /** Sampling cadence while recording (2 ticks = 10 Hz). */
  private static final int SAMPLE_INTERVAL_TICKS = 2;

  /**
   * A detected train within this horizontal distance of a prior one keeps its track id (blocks).
   */
  private static final double TRACK_MATCH_RADIUS = 24.0;

  /** Flush the writer to disk every this many samples. */
  private static final int FLUSH_EVERY = 10;

  private static final Gson GSON = new Gson();
  private static final DateTimeFormatter FILE_STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  private boolean recording = false;
  private BufferedWriter writer;
  private Path file;
  private long startMs;
  private long lastSampleTick = Long.MIN_VALUE;
  private int sampleCount;
  private int sinceFlush;
  private int nextTrackId;
  private List<TrackPoint> lastTracks = new ArrayList<>();

  private RctCaptureRecorder() {}

  public static RctCaptureRecorder getInstance() {
    return INSTANCE;
  }

  public boolean isRecording() {
    return recording;
  }

  public void start() {
    if (recording) {
      ImfChat.sendWarn("RCT capture already running — /imf rct capture stop first.");
      return;
    }
    Minecraft client = Minecraft.getInstance();
    if (client.player == null || client.level == null) {
      ImfChat.sendError("Can't start RCT capture: not in a world.");
      return;
    }
    try {
      file =
          ImfStorage.rctCaptureDir()
              .resolve("rct-" + LocalDateTime.now().format(FILE_STAMP) + ".ndjson");
      writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
      startMs = System.currentTimeMillis();
      lastSampleTick = Long.MIN_VALUE;
      sampleCount = 0;
      sinceFlush = 0;
      nextTrackId = 0;
      lastTracks = new ArrayList<>();

      Map<String, Object> header = new LinkedHashMap<>();
      header.put("type", "session");
      header.put("v", 1);
      header.put("startedMs", startMs);
      header.put("dimension", client.level.dimension().identifier().toString());
      header.put("sampleHz", 20.0 / SAMPLE_INTERVAL_TICKS);
      header.put(
          "schema",
          "lines: type=session|sample|mark; sample{t=epochMs, tick=worldGameTime,"
              + " player=[x,y,z], trains[]{id=session track, c=centroid[x,y,z],"
              + " cars=[[x,y,z,damage],...]}, rideElapsed=sec/ridePct=% when on RCT}");
      writeLine(header);
      writer.flush();
      recording = true;

      ImfChat.sendSuccess("RCT capture started → " + file.getFileName());
      ImfChat.send(
          "Follow a train to log its path. "
              + ImfChat.YELLOW
              + "/imf rct capture mark <label>"
              + ImfChat.WHITE
              + " to tag a spot, "
              + ImfChat.YELLOW
              + "stop"
              + ImfChat.WHITE
              + " when done.");
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.error("Failed to start RCT capture", e);
      ImfChat.sendError("Couldn't open capture file: " + e.getMessage());
      closeQuietly();
      recording = false;
    }
  }

  public void stop() {
    if (!recording) {
      ImfChat.sendWarn("RCT capture isn't running.");
      return;
    }
    long durationSec = (System.currentTimeMillis() - startMs) / 1000;
    Path saved = file;
    closeQuietly();
    recording = false;
    ImfChat.sendSuccess(
        "RCT capture saved: "
            + sampleCount
            + " samples over "
            + durationSec
            + "s → "
            + (saved == null ? "?" : saved.getFileName()));
  }

  public void status() {
    if (!recording) {
      ImfChat.send(
          "RCT capture: "
              + ImfChat.YELLOW
              + "idle"
              + ImfChat.WHITE
              + ". Start with /imf rct capture start.");
      return;
    }
    long durationSec = (System.currentTimeMillis() - startMs) / 1000;
    ImfChat.send(
        "RCT capture: "
            + ImfChat.GREEN
            + "recording"
            + ImfChat.WHITE
            + " — "
            + sampleCount
            + " samples, "
            + durationSec
            + "s → "
            + (file == null ? "?" : file.getFileName()));
  }

  /**
   * Writes a one-off labelled sample (e.g. a station or endpoint) at the current train positions.
   */
  public void mark(String label) {
    if (!recording) {
      ImfChat.sendWarn("Start a capture first: /imf rct capture start.");
      return;
    }
    Minecraft client = Minecraft.getInstance();
    if (client.player == null || client.level == null) {
      return;
    }
    List<Cluster> clusters = RctTrains.detect(client);
    Map<String, Object> line = sampleMap("mark", client, clusters);
    line.put("label", label == null ? "" : label);
    try {
      writeLine(line);
      writer.flush();
      ImfChat.sendSuccess(
          "Marked “" + (label == null || label.isEmpty() ? "(unlabelled)" : label) + "”");
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.error("RCT capture mark failed", e);
    }
  }

  /** Called every client tick; no-op unless a capture is running. */
  public void tick(Minecraft client) {
    if (!recording || client.player == null || client.level == null) {
      return;
    }
    long worldTick = client.level.getGameTime();
    if (lastSampleTick != Long.MIN_VALUE && worldTick - lastSampleTick < SAMPLE_INTERVAL_TICKS) {
      return;
    }
    List<Cluster> clusters = RctTrains.detect(client);
    if (clusters.isEmpty()) {
      return; // only log when at least one train is in view
    }
    lastSampleTick = worldTick;

    try {
      writeLine(sampleMap("sample", client, clusters));
      sampleCount++;
      if (++sinceFlush >= FLUSH_EVERY) {
        writer.flush();
        sinceFlush = 0;
      }
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.error("RCT capture write failed", e);
      ImfChat.sendError("RCT capture write failed; stopping.");
      closeQuietly();
      recording = false;
    }
  }

  /** Closes the file without chat output (player left the world / disconnected). */
  public void stopOnDisconnect() {
    if (recording) {
      closeQuietly();
      recording = false;
    }
  }

  /** Builds a sample/mark line and assigns continuity track ids to the detected trains. */
  private Map<String, Object> sampleMap(String type, Minecraft client, List<Cluster> clusters) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("type", type);
    line.put("t", System.currentTimeMillis());
    line.put("tick", client.level.getGameTime());
    // Associate the sample with the ride clock when riding the Red Car Trolley (from the
    // scoreboard).
    if (CurrentRideHolder.getCurrentRide() == RideName.RED_CAR_TROLLEY) {
      Integer elapsed = CurrentRideHolder.getElapsedSeconds();
      Integer pct = CurrentRideHolder.getCurrentProgressPercent();
      if (elapsed != null) {
        line.put("rideElapsed", elapsed);
      }
      if (pct != null) {
        line.put("ridePct", pct);
      }
    }
    line.put(
        "player",
        Arrays.asList(
            round(client.player.getX()), round(client.player.getY()), round(client.player.getZ())));

    List<TrackPoint> current = new ArrayList<>();
    boolean[] used = new boolean[lastTracks.size()];
    List<Map<String, Object>> trains = new ArrayList<>();
    for (Cluster c : clusters) {
      int bestIdx = -1;
      double bestSq = TRACK_MATCH_RADIUS * TRACK_MATCH_RADIUS;
      for (int i = 0; i < lastTracks.size(); i++) {
        if (used[i]) {
          continue;
        }
        double d = horizDistSq(c.cx(), c.cz(), lastTracks.get(i).x, lastTracks.get(i).z);
        if (d <= bestSq) {
          bestSq = d;
          bestIdx = i;
        }
      }
      int id;
      if (bestIdx >= 0) {
        id = lastTracks.get(bestIdx).id;
        used[bestIdx] = true;
      } else {
        id = nextTrackId++;
      }
      current.add(new TrackPoint(id, c.cx(), c.cz()));
      trains.add(trainMap(id, c));
    }
    lastTracks = current;
    line.put("trains", trains);
    return line;
  }

  private static Map<String, Object> trainMap(int id, Cluster c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("c", Arrays.asList(round(c.cx()), round(c.cy()), round(c.cz())));
    List<List<Number>> cars = new ArrayList<>();
    for (Car car : c.cars()) {
      List<Number> row = new ArrayList<>();
      row.add(round(car.x()));
      row.add(round(car.y()));
      row.add(round(car.z()));
      row.add(car.damage());
      cars.add(row);
    }
    m.put("cars", cars);
    return m;
  }

  private void writeLine(Map<String, Object> obj) throws IOException {
    writer.write(GSON.toJson(obj));
    writer.newLine();
  }

  private void closeQuietly() {
    if (writer != null) {
      try {
        writer.flush();
        writer.close();
      } catch (IOException ignored) {
        // Nothing useful to do on close failure.
      }
      writer = null;
    }
  }

  private static double round(double v) {
    return Math.round(v * 1000.0) / 1000.0;
  }

  private static double horizDistSq(double ax, double az, double bx, double bz) {
    double dx = ax - bx;
    double dz = az - bz;
    return dx * dx + dz * dz;
  }

  private static final class TrackPoint {
    final int id;
    final double x;
    final double z;

    TrackPoint(int id, double x, double z) {
      this.id = id;
      this.x = x;
      this.z = z;
    }
  }
}
