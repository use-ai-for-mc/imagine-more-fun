package com.chenweikeng.imf.nra.coaster;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.ride.RideName;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * Per-ride coaster path registry — the baked vehicle recordings shared by every consumer that needs
 * a coaster's track shape.
 *
 * <p>Each registered {@link RideName} maps to a {@link TrackSamples} holding the parsed IFTC v1/v2
 * samples (x/y/z/yaw/pitch + signed roll). Tracks are loaded once at class-init from {@code
 * /imaginemorefun/<resource>.bin}; missing resources log at INFO and become an empty {@code
 * TrackSamples} (count=0, {@link TrackSamples#nearestSample} returns -1), so callers can register a
 * coaster before its track has been recorded without crashing.
 *
 * <p>Both {@code SPACE_MOUNTAIN} and {@code HYPERSPACE_MOUNTAIN} share {@code dome_track.bin} —
 * they're the same physical coaster on the ImagineFun server.
 *
 * <p>The only consumer is {@code SpaceMountainTrackRenderer}, which banks the baked coaster-tube
 * geometry by the per-sample roll. Camera-lean banking no longer reads this — it amplifies
 * SmoothCoasters' live tilt instead (see {@code CoasterTiltAmplifier}). Add a new coaster: drop
 * {@code track_<short>.bin} into {@code src/main/resources/imaginemorefun/} and add a {@code
 * RESOURCES} entry here.
 */
public final class CoasterTrackData {

  /**
   * Parsed coaster path samples. Positions are world-space (no render offsets); angles are degrees.
   * An empty instance ({@code count == 0}) is returned for rides whose resource hasn't been baked
   * yet.
   */
  public static final class TrackSamples {
    public final int count;
    public final double[] x;
    public final double[] y;
    public final double[] z;
    public final float[] yaw;
    public final float[] pitch;

    /** Signed bank angle per sample, degrees. All zeros for a v1 binary. */
    public final float[] roll;

    TrackSamples(
        int count, double[] x, double[] y, double[] z, float[] yaw, float[] pitch, float[] roll) {
      this.count = count;
      this.x = x;
      this.y = y;
      this.z = z;
      this.yaw = yaw;
      this.pitch = pitch;
      this.roll = roll;
    }

    static TrackSamples empty() {
      return new TrackSamples(
          0, new double[0], new double[0], new double[0], new float[0], new float[0], new float[0]);
    }

    /**
     * Index of the sample nearest (3D Euclidean) to the given world position, or {@code -1} if no
     * track is loaded. A full O(n) scan — a thousand-odd samples is trivial per client tick.
     */
    public int nearestSample(double px, double py, double pz) {
      int best = -1;
      double bestSq = Double.MAX_VALUE;
      for (int i = 0; i < count; i++) {
        double dx = x[i] - px;
        double dy = y[i] - py;
        double dz = z[i] - pz;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 < bestSq) {
          bestSq = d2;
          best = i;
        }
      }
      return best;
    }
  }

  // RideName → resource path under /imaginemorefun/. SM and HSM share dome_track.bin — same
  // physical ride.
  private static final Map<RideName, String> RESOURCES =
      Map.ofEntries(
          Map.entry(RideName.SPACE_MOUNTAIN, "/imaginemorefun/dome_track.bin"),
          Map.entry(RideName.HYPERSPACE_MOUNTAIN, "/imaginemorefun/dome_track.bin"));

  private static final EnumMap<RideName, TrackSamples> TRACKS = new EnumMap<>(RideName.class);

  static {
    for (Map.Entry<RideName, String> e : RESOURCES.entrySet()) {
      TRACKS.put(e.getKey(), loadTrack(e.getKey(), e.getValue()));
    }
  }

  private CoasterTrackData() {}

  /**
   * Parsed samples for the given ride. Never null — returns an empty {@link TrackSamples} if the
   * ride isn't registered or its resource failed to load.
   */
  public static TrackSamples forRide(RideName ride) {
    if (ride == null) return TrackSamples.empty();
    TrackSamples s = TRACKS.get(ride);
    return s != null ? s : TrackSamples.empty();
  }

  /** True iff this ride has a non-empty baked track. */
  public static boolean hasTrack(RideName ride) {
    return forRide(ride).count > 0;
  }

  private static TrackSamples loadTrack(RideName ride, String resource) {
    try (InputStream in = CoasterTrackData.class.getResourceAsStream(resource)) {
      if (in == null) {
        NotRidingAlertClient.LOGGER.info(
            "[CoasterTrackData] no track for {} at {} — banking disabled for this ride",
            ride,
            resource);
        return TrackSamples.empty();
      }
      DataInputStream dis = new DataInputStream(in);
      byte[] magic = new byte[4];
      dis.readFully(magic);
      if (magic[0] != 'I' || magic[1] != 'F' || magic[2] != 'T' || magic[3] != 'C') {
        throw new IOException("bad track magic: " + new String(magic));
      }
      int version = dis.readUnsignedByte();
      if (version != 1 && version != 2) {
        throw new IOException("unsupported track version: " + version);
      }
      int n = dis.readInt();
      double[] xx = new double[n];
      double[] yy = new double[n];
      double[] zz = new double[n];
      float[] yw = new float[n];
      float[] pt = new float[n];
      float[] rl = new float[n];
      for (int i = 0; i < n; i++) {
        xx[i] = dis.readDouble();
        yy[i] = dis.readDouble();
        zz[i] = dis.readDouble();
        yw[i] = dis.readFloat();
        pt[i] = dis.readFloat();
        rl[i] = (version >= 2) ? dis.readFloat() : 0f;
      }
      NotRidingAlertClient.LOGGER.info(
          "[CoasterTrackData] loaded {} samples for {} (IFTC v{})", n, ride, version);
      return new TrackSamples(n, xx, yy, zz, yw, pt, rl);
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.error(
          "[CoasterTrackData] load failed for {} ({}): {}", ride, resource, e.toString());
      return TrackSamples.empty();
    }
  }
}
