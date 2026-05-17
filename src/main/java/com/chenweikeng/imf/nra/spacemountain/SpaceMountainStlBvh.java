package com.chenweikeng.imf.nra.spacemountain;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Bounding-volume hierarchy over the Space Mountain STL's world-space triangles, for fast
 * ray-vs-mesh queries. The disco-ball star projector raycasts thousands of beams against it per
 * frame, which a naive 95k-triangle scan could not sustain.
 *
 * <p>Median-split build over triangle centroids; iterative slab-test traversal with
 * Moeller-Trumbore triangle intersection. The STL never moves, so the tree is built once and
 * reused.
 */
public final class SpaceMountainStlBvh {
  private static final int LEAF_SIZE = 4;
  private static final double EPS = 1e-7;

  private final double[] verts; // world-space, 9 doubles per triangle (3 verts x xyz)
  private final int[] order; // triangle indices, contiguous within each leaf
  private final Node root;

  /** Plain node: an AABB plus either two children or a leaf range into {@link #order}. */
  private static final class Node {
    double minX, minY, minZ, maxX, maxY, maxZ;
    Node left, right; // both null => leaf
    int start, count; // range into `order` (leaf only)
  }

  public SpaceMountainStlBvh(double[] worldVerts, int triCount) {
    this.verts = worldVerts;
    Integer[] idx = new Integer[triCount];
    double[] cx = new double[triCount];
    double[] cy = new double[triCount];
    double[] cz = new double[triCount];
    for (int t = 0; t < triCount; t++) {
      idx[t] = t;
      int b = t * 9;
      cx[t] = (worldVerts[b] + worldVerts[b + 3] + worldVerts[b + 6]) / 3.0;
      cy[t] = (worldVerts[b + 1] + worldVerts[b + 4] + worldVerts[b + 7]) / 3.0;
      cz[t] = (worldVerts[b + 2] + worldVerts[b + 5] + worldVerts[b + 8]) / 3.0;
    }
    this.root = triCount == 0 ? new Node() : build(idx, 0, triCount, cx, cy, cz);
    this.order = new int[triCount];
    for (int i = 0; i < triCount; i++) this.order[i] = idx[i];
  }

  private Node build(Integer[] idx, int start, int end, double[] cx, double[] cy, double[] cz) {
    Node n = new Node();
    n.minX = n.minY = n.minZ = Double.POSITIVE_INFINITY;
    n.maxX = n.maxY = n.maxZ = Double.NEGATIVE_INFINITY;
    for (int i = start; i < end; i++) {
      int b = idx[i] * 9;
      for (int v = 0; v < 9; v += 3) {
        double x = verts[b + v], y = verts[b + v + 1], z = verts[b + v + 2];
        if (x < n.minX) n.minX = x;
        if (x > n.maxX) n.maxX = x;
        if (y < n.minY) n.minY = y;
        if (y > n.maxY) n.maxY = y;
        if (z < n.minZ) n.minZ = z;
        if (z > n.maxZ) n.maxZ = z;
      }
    }
    int count = end - start;
    if (count <= LEAF_SIZE) {
      n.start = start;
      n.count = count;
      return n;
    }
    // Split on the longest axis of the centroid bounds.
    double cMinX = Double.POSITIVE_INFINITY, cMaxX = Double.NEGATIVE_INFINITY;
    double cMinY = Double.POSITIVE_INFINITY, cMaxY = Double.NEGATIVE_INFINITY;
    double cMinZ = Double.POSITIVE_INFINITY, cMaxZ = Double.NEGATIVE_INFINITY;
    for (int i = start; i < end; i++) {
      int t = idx[i];
      if (cx[t] < cMinX) cMinX = cx[t];
      if (cx[t] > cMaxX) cMaxX = cx[t];
      if (cy[t] < cMinY) cMinY = cy[t];
      if (cy[t] > cMaxY) cMaxY = cy[t];
      if (cz[t] < cMinZ) cMinZ = cz[t];
      if (cz[t] > cMaxZ) cMaxZ = cz[t];
    }
    double exX = cMaxX - cMinX, exY = cMaxY - cMinY, exZ = cMaxZ - cMinZ;
    int axis = (exX >= exY && exX >= exZ) ? 0 : (exY >= exZ ? 1 : 2);
    final double[] key = axis == 0 ? cx : (axis == 1 ? cy : cz);
    Arrays.sort(idx, start, end, Comparator.<Integer>comparingDouble(t -> key[t]));
    int mid = (start + end) >>> 1;
    n.left = build(idx, start, mid, cx, cy, cz);
    n.right = build(idx, mid, end, cx, cy, cz);
    return n;
  }

  /**
   * Nearest hit distance along the unit-length ray, within (EPS, maxT]. Returns {@link
   * Double#MAX_VALUE} when the ray misses the mesh.
   */
  public double raycast(
      double ox, double oy, double oz, double dx, double dy, double dz, double maxT) {
    double invDx = 1.0 / dx, invDy = 1.0 / dy, invDz = 1.0 / dz;
    double best = maxT;
    Node[] stack = new Node[64];
    int sp = 0;
    stack[sp++] = root;
    while (sp > 0) {
      Node n = stack[--sp];
      if (!hitsAabb(n, ox, oy, oz, invDx, invDy, invDz, best)) continue;
      if (n.left == null) {
        int e = n.start + n.count;
        for (int i = n.start; i < e; i++) {
          double t = rayTriangle(order[i], ox, oy, oz, dx, dy, dz);
          if (t > EPS && t < best) best = t;
        }
      } else {
        if (sp + 2 > stack.length) stack = Arrays.copyOf(stack, stack.length * 2);
        stack[sp++] = n.left;
        stack[sp++] = n.right;
      }
    }
    return best < maxT ? best : Double.MAX_VALUE;
  }

  private static boolean hitsAabb(
      Node n,
      double ox,
      double oy,
      double oz,
      double invDx,
      double invDy,
      double invDz,
      double maxT) {
    double tx1 = (n.minX - ox) * invDx, tx2 = (n.maxX - ox) * invDx;
    double tmin = Math.min(tx1, tx2), tmax = Math.max(tx1, tx2);
    double ty1 = (n.minY - oy) * invDy, ty2 = (n.maxY - oy) * invDy;
    tmin = Math.max(tmin, Math.min(ty1, ty2));
    tmax = Math.min(tmax, Math.max(ty1, ty2));
    double tz1 = (n.minZ - oz) * invDz, tz2 = (n.maxZ - oz) * invDz;
    tmin = Math.max(tmin, Math.min(tz1, tz2));
    tmax = Math.min(tmax, Math.max(tz1, tz2));
    return tmax >= Math.max(tmin, 0.0) && tmin < maxT;
  }

  /** Moeller-Trumbore, double-sided. Returns the ray parameter t, or a negative value on miss. */
  private double rayTriangle(
      int tri, double ox, double oy, double oz, double dx, double dy, double dz) {
    int b = tri * 9;
    double v0x = verts[b], v0y = verts[b + 1], v0z = verts[b + 2];
    double e1x = verts[b + 3] - v0x, e1y = verts[b + 4] - v0y, e1z = verts[b + 5] - v0z;
    double e2x = verts[b + 6] - v0x, e2y = verts[b + 7] - v0y, e2z = verts[b + 8] - v0z;
    double hx = dy * e2z - dz * e2y;
    double hy = dz * e2x - dx * e2z;
    double hz = dx * e2y - dy * e2x;
    double a = e1x * hx + e1y * hy + e1z * hz;
    if (a > -EPS && a < EPS) return -1.0; // ray parallel to the triangle
    double f = 1.0 / a;
    double sx = ox - v0x, sy = oy - v0y, sz = oz - v0z;
    double u = f * (sx * hx + sy * hy + sz * hz);
    if (u < 0.0 || u > 1.0) return -1.0;
    double qx = sy * e1z - sz * e1y;
    double qy = sz * e1x - sx * e1z;
    double qz = sx * e1y - sy * e1x;
    double v = f * (dx * qx + dy * qy + dz * qz);
    if (v < 0.0 || u + v > 1.0) return -1.0;
    return f * (e2x * qx + e2y * qy + e2z * qz);
  }
}
