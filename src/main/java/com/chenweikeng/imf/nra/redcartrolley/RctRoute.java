package com.chenweikeng.imf.nra.redcartrolley;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The baked Red Car Trolley route, derived offline from a movement capture and keyed to the
 * server's ride clock. Loaded once from {@code rct-route.json}.
 *
 * <p>Model: a shared-track shuttle between two reversal termini — GotG (arc-length {@code s=0}) and
 * Buena Vista / the gate ({@code s=L}). Each one-way leg is {@link #legSeconds} of ride clock
 * ({@code rideElapsed} 0→306); {@code sFwd[t]}/{@code sRev[t]} give arc-length at ride-second t on
 * the GotG→BV and BV→GotG legs. A full cycle is leg + Buena-Vista dwell + leg + GotG dwell; cycle
 * phase {@code phi} is measured in seconds. The two trains run exactly {@link #phaseOffsetSec}
 * apart (half a cycle), which is why they meet in the middle.
 */
public final class RctRoute {
  private static final String RESOURCE = "/assets/not-riding-alert/rct-route.json";
  private static final double TERM_EPS = 5.0; // within this of s=0 / s=L counts as at a terminus
  private static RctRoute instance;

  private final double[] px;
  private final double[] py;
  private final double[] pz;
  private final double[] cum;
  private final double length;
  private final int legSeconds;
  private final double dwellGotG;
  private final double dwellBV;
  private final double cycleSeconds;
  private final double phaseOffsetSec;
  private final double[] sFwd; // GotG→BV: arc-length at rideElapsed t (length legSeconds+1)
  private final double[] sRev; // BV→GotG: arc-length at rideElapsed t
  private final String[] stopNames;
  private final double[] stopS;

  /** Arrival of a train at a stop: which stop and how many seconds away. */
  public record StopEta(String name, double etaSec) {}

  /** A train parked at a station: which station, and how many seconds until it departs. */
  public record Dwell(String station, double departSec) {}

  private RctRoute(Data d) {
    px = new double[d.polyline.length];
    py = new double[d.polyline.length];
    pz = new double[d.polyline.length];
    for (int i = 0; i < d.polyline.length; i++) {
      px[i] = d.polyline[i][0];
      py[i] = d.polyline[i][1];
      pz[i] = d.polyline[i][2];
    }
    cum = new double[px.length];
    for (int i = 1; i < px.length; i++) {
      cum[i] = cum[i - 1] + Math.hypot(px[i] - px[i - 1], pz[i] - pz[i - 1]);
    }
    length = cum[cum.length - 1];
    legSeconds = d.legSeconds;
    dwellGotG = d.dwellGotG;
    dwellBV = d.dwellBV;
    cycleSeconds = d.cycleSeconds;
    phaseOffsetSec = d.phaseOffsetSec;
    sFwd = d.sFwd;
    sRev = d.sRev;
    stopNames = new String[d.stations.length];
    stopS = new double[d.stations.length];
    for (int i = 0; i < d.stations.length; i++) {
      stopNames[i] = d.stations[i].name;
      stopS[i] = d.stations[i].s;
    }
  }

  /** Loads the route once; returns {@code null} if the resource is missing or malformed. */
  public static RctRoute get() {
    if (instance == null) {
      instance = load();
    }
    return instance;
  }

  private static RctRoute load() {
    try (InputStream is = RctRoute.class.getResourceAsStream(RESOURCE)) {
      if (is == null) {
        NotRidingAlertClient.LOGGER.warn("RCT route resource missing: {}", RESOURCE);
        return null;
      }
      Data d = new Gson().fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), Data.class);
      if (d == null
          || d.polyline == null
          || d.sFwd == null
          || d.sRev == null
          || d.stations == null
          || d.cycleSeconds <= 0) {
        return null;
      }
      RctRoute r = new RctRoute(d);
      NotRidingAlertClient.LOGGER.info(
          "Loaded RCT route: L={}, leg={}s, cycle={}s",
          (int) r.length,
          r.legSeconds,
          (int) r.cycleSeconds);
      return r;
    } catch (Exception e) {
      NotRidingAlertClient.LOGGER.error("Failed to load RCT route", e);
      return null;
    }
  }

  public double length() {
    return length;
  }

  /** Route polyline for a top-down map: point count and per-index world x / z. */
  public int polyN() {
    return px.length;
  }

  public double polyX(int i) {
    return px[i];
  }

  public double polyZ(int i) {
    return pz[i];
  }

  public int legSeconds() {
    return legSeconds;
  }

  public double cycleSeconds() {
    return cycleSeconds;
  }

  /** Phase of the partner train: half a cycle ahead. */
  public double otherPhase(double phi) {
    return mod(phi + phaseOffsetSec, cycleSeconds);
  }

  /** Cycle phase (seconds) for a train currently riding {@code leg} (+1 GotG→BV, −1 BV→GotG). */
  public double phaseFromRide(int leg, double rideElapsed) {
    double t = Math.max(0, Math.min(legSeconds, rideElapsed));
    return leg >= 0 ? t : legSeconds + dwellBV + t;
  }

  /** Cycle phase at which a train pulls into the given terminus (start of its dwell). */
  public double arrivalPhase(boolean gotg) {
    return gotg ? 2.0 * legSeconds + dwellBV : legSeconds;
  }

  /** True if phase {@code phi} falls within the given terminus's dwell window. */
  public boolean inDwell(double phi, boolean gotg) {
    double start = arrivalPhase(gotg);
    double end = start + (gotg ? dwellGotG : dwellBV);
    double p = mod(phi, cycleSeconds);
    return p >= start && p < end;
  }

  /** Best-guess cycle phase for a seen train at arc-length {@code s} travelling {@code dir}. */
  public double phaseFromPos(double s, int dir) {
    if (dir > 0) {
      return nearestSecond(sFwd, s);
    }
    if (dir < 0) {
      return legSeconds + dwellBV + nearestSecond(sRev, s);
    }
    double tf = nearestSecond(sFwd, s);
    double tr = nearestSecond(sRev, s);
    return Math.abs(sFwd[(int) tf] - s) <= Math.abs(sRev[(int) tr] - s)
        ? tf
        : legSeconds + dwellBV + tr;
  }

  /** Which leg a riding train is on, from its position vs the two legs' position at {@code t}. */
  public int legAt(double rideElapsed, double x, double z) {
    int t = (int) Math.max(0, Math.min(legSeconds, Math.round(rideElapsed)));
    double[] f = posAt(sFwd[t]);
    double[] r = posAt(sRev[t]);
    double df = (x - f[0]) * (x - f[0]) + (z - f[2]) * (z - f[2]);
    double dr = (x - r[0]) * (x - r[0]) + (z - r[2]) * (z - r[2]);
    return df <= dr ? 1 : -1;
  }

  /** Arc-length of a train at cycle phase {@code phi} (seconds). */
  public double sAtPhase(double phi) {
    phi = mod(phi, cycleSeconds);
    if (phi < legSeconds) {
      return sFwd[(int) Math.round(phi)];
    }
    phi -= legSeconds;
    if (phi < dwellBV) {
      return length; // dwelling at Buena Vista
    }
    phi -= dwellBV;
    if (phi < legSeconds) {
      return sRev[(int) Math.round(phi)];
    }
    return 0; // dwelling at GotG
  }

  /** Travel direction at phase {@code phi}: +1 toward Buena Vista, −1 toward GotG, 0 dwelling. */
  public int dirAtPhase(double phi) {
    phi = mod(phi, cycleSeconds);
    if (phi < legSeconds) {
      return 1;
    }
    phi -= legSeconds;
    if (phi < dwellBV) {
      return 0;
    }
    phi -= dwellBV;
    return phi < legSeconds ? -1 : 0;
  }

  /** The next terminus a train at phase {@code phi} pulls into, and the seconds until it does. */
  public StopEta nextStop(double phi) {
    double prev = sAtPhase(phi);
    for (int dt = 1; dt <= (int) cycleSeconds; dt++) {
      double s = sAtPhase(phi + dt);
      if (s <= TERM_EPS && prev > TERM_EPS) {
        return new StopEta(nameAt(0), dt);
      }
      if (s >= length - TERM_EPS && prev < length - TERM_EPS) {
        return new StopEta(nameAt(length), dt);
      }
      prev = s;
    }
    return null;
  }

  /** If the train at phase {@code phi} is parked at a station, its name + seconds to departure. */
  public Dwell dwellAt(double phi) {
    double p = mod(phi, cycleSeconds);
    if (p < legSeconds) {
      return null; // GotG → Buena Vista
    }
    p -= legSeconds;
    if (p < dwellBV) {
      return new Dwell(nameAt(length), dwellBV - p); // dwelling at Buena Vista
    }
    p -= dwellBV;
    if (p < legSeconds) {
      return null; // Buena Vista → GotG
    }
    p -= legSeconds;
    return new Dwell(nameAt(0), dwellGotG - p); // dwelling at GotG
  }

  private String nameAt(double s) {
    String best = "?";
    double bd = Double.MAX_VALUE;
    for (int i = 0; i < stopS.length; i++) {
      double d = Math.abs(stopS[i] - s);
      if (d < bd) {
        bd = d;
        best = stopNames[i];
      }
    }
    return best;
  }

  /** Nearest arc-length on the polyline to a world (x, z). */
  public double snapS(double qx, double qz) {
    double bestS = 0;
    double bestD = Double.MAX_VALUE;
    for (int i = 0; i < px.length - 1; i++) {
      double dx = px[i + 1] - px[i];
      double dz = pz[i + 1] - pz[i];
      double seg = dx * dx + dz * dz;
      if (seg == 0) {
        continue;
      }
      double t = Math.max(0, Math.min(1, ((qx - px[i]) * dx + (qz - pz[i]) * dz) / seg));
      double cxp = px[i] + t * dx;
      double czp = pz[i] + t * dz;
      double d = (qx - cxp) * (qx - cxp) + (qz - czp) * (qz - czp);
      if (d < bestD) {
        bestD = d;
        bestS = cum[i] + t * Math.sqrt(seg);
      }
    }
    return bestS;
  }

  /** World position (x, y, z) at arc-length {@code s}. */
  public double[] posAt(double s) {
    s = Math.max(0, Math.min(length, s));
    int lo = 0;
    int hi = cum.length - 1;
    while (lo < hi - 1) {
      int mid = (lo + hi) >>> 1;
      if (cum[mid] <= s) {
        lo = mid;
      } else {
        hi = mid;
      }
    }
    double segLen = cum[hi] - cum[lo];
    double f = segLen > 0 ? (s - cum[lo]) / segLen : 0;
    return new double[] {
      px[lo] + f * (px[hi] - px[lo]), py[lo] + f * (py[hi] - py[lo]), pz[lo] + f * (pz[hi] - pz[lo])
    };
  }

  private double nearestSecond(double[] arr, double s) {
    int best = 0;
    double bd = Double.MAX_VALUE;
    for (int t = 0; t < arr.length; t++) {
      double d = Math.abs(arr[t] - s);
      if (d < bd) {
        bd = d;
        best = t;
      }
    }
    return best;
  }

  private static double mod(double a, double m) {
    double r = a % m;
    return r < 0 ? r + m : r;
  }

  private static final class Data {
    int legSeconds;
    double dwellGotG;
    double dwellBV;
    double cycleSeconds;
    double phaseOffsetSec;
    double[][] polyline;
    double[] sFwd;
    double[] sRev;
    Stop[] stations;
  }

  private static final class Stop {
    String name;
    double s;
  }
}
