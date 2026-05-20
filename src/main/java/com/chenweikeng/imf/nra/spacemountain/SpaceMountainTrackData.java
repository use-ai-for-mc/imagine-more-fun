package com.chenweikeng.imf.nra.spacemountain;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Parsed Space/Hyperspace Mountain coaster path — the baked vehicle recording shared by every
 * consumer that needs the track shape.
 *
 * <p>Loaded once from {@code dome_track.bin} (magic {@code IFTC}) at class-load. Two on-disk
 * versions are accepted:
 *
 * <ul>
 *   <li><b>v1</b> — {@code x,y,z,yaw,pitch} per sample (32 bytes). {@link #roll} is all zeros.
 *   <li><b>v2</b> — adds a signed {@code roll} (bank) angle per sample (36 bytes), baked offline by
 *       {@code bake-roll.py} from the path curvature.
 * </ul>
 *
 * <p>{@link SpaceMountainTrackRenderer} builds the rail geometry from this; {@link
 * SpaceMountainCameraBank} looks up the per-sample roll to tilt the rider through turns. Positions
 * are raw world-space (no render offsets applied); all angles are degrees.
 */
public final class SpaceMountainTrackData {
  private static final String RESOURCE = "/imaginemorefun/dome_track.bin";

  /** Number of baked samples; 0 if the resource failed to load. */
  public static final int count;

  public static final double[] x;
  public static final double[] y;
  public static final double[] z;
  public static final float[] yaw;
  public static final float[] pitch;

  /** Signed bank angle per sample, degrees. All zeros for a v1 binary. */
  public static final float[] roll;

  static {
    int n = 0;
    double[] xx = new double[0];
    double[] yy = new double[0];
    double[] zz = new double[0];
    float[] yw = new float[0];
    float[] pt = new float[0];
    float[] rl = new float[0];
    try (InputStream in = SpaceMountainTrackData.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        NotRidingAlertClient.LOGGER.error(
            "[SpaceMountainTrackData] resource {} not found — track effects disabled", RESOURCE);
      } else {
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
        n = dis.readInt();
        xx = new double[n];
        yy = new double[n];
        zz = new double[n];
        yw = new float[n];
        pt = new float[n];
        rl = new float[n];
        for (int i = 0; i < n; i++) {
          xx[i] = dis.readDouble();
          yy[i] = dis.readDouble();
          zz[i] = dis.readDouble();
          yw[i] = dis.readFloat();
          pt[i] = dis.readFloat();
          rl[i] = (version >= 2) ? dis.readFloat() : 0f;
        }
        NotRidingAlertClient.LOGGER.info(
            "[SpaceMountainTrackData] loaded {} samples (IFTC v{})", n, version);
      }
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.error("[SpaceMountainTrackData] load failed", e);
      n = 0;
      xx = new double[0];
      yy = new double[0];
      zz = new double[0];
      yw = new float[0];
      pt = new float[0];
      rl = new float[0];
    }
    count = n;
    x = xx;
    y = yy;
    z = zz;
    yaw = yw;
    pitch = pt;
    roll = rl;
  }

  private SpaceMountainTrackData() {}

  /**
   * Index of the baked sample nearest (3D Euclidean) to the given world position, or {@code -1} if
   * no track is loaded. A full scan — ~1200 samples is trivial per tick.
   */
  public static int nearestSample(double px, double py, double pz) {
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
