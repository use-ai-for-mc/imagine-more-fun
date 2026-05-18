package com.chenweikeng.imf.nra.spacemountain;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * In-game port of the retired {@code bake-borders.py} "watertight" flood-fill.
 *
 * <p>The original pipeline dumped chunks with {@code /imf dumpchunks} and baked the dome-wall faces
 * with a Python script. That command was removed, so the watertight calculation now runs in the mod
 * directly: a {@code getBlockState} flood-fill is far too many reads to drive over the DebugBridge,
 * but in-process Java handles it in a single brief pass.
 *
 * <p>Algorithm (unchanged from {@code bake-borders.py}):
 *
 * <ol>
 *   <li>Flood-fill from the player's position through air cells, hard-clipped to {@code DOME_BBOX}
 *       and capped at {@code MAX_VISITED} so a leaked dome can't flood the world.
 *   <li>Every air&rarr;solid transition is a wall face; the outward normal points from the wall
 *       block back toward the flooded cell.
 *   <li>Barrier/light (invisible) and yellow_glazed_terracotta (the watertight-boundary markers)
 *       are dropped — they seal the flood but must not carry stars.
 *   <li>The faces are written to {@code config/imaginemorefun/dome_borders.bin} in the same IFDB v1
 *       format {@link SpaceMountainStarRenderer} already reads.
 * </ol>
 *
 * <p>Bridge entry point: stand inside the dome and call {@link #bake()}.
 */
public final class SpaceMountainBorderBake {

  // Hard clip — the flood is confined to this box so a leaked dome (an opening in the wall) can't
  // flood the whole world. Matches bake-borders.py's DOME_BBOX.
  private static final int MIN_X = -330;
  private static final int MAX_X = -200;
  private static final int MIN_Y = 50;
  private static final int MAX_Y = 120;
  private static final int MIN_Z = 100;
  private static final int MAX_Z = 230;
  private static final int MAX_VISITED = 250_000;

  private static final Direction[] DIRS = Direction.values();

  private static final Path OUT_PATH =
      FabricLoader.getInstance()
          .getConfigDir()
          .resolve("imaginemorefun")
          .resolve("dome_borders.bin");

  private SpaceMountainBorderBake() {}

  /**
   * Flood-fill the dome interior from the player's position and write the visible wall faces to
   * {@code config/imaginemorefun/dome_borders.bin} (IFDB v1). Stand inside the dome and call this
   * over the DebugBridge. Returns a human-readable summary.
   */
  public static String bake() {
    Minecraft mc = Minecraft.getInstance();
    ClientLevel level = mc.level;
    LocalPlayer player = mc.player;
    if (level == null || player == null) {
      return "[BorderBake] no world loaded";
    }

    // Seed: the first air cell at or just above the player's feet.
    BlockPos feet = player.blockPosition();
    int px = feet.getX();
    int py = feet.getY();
    int pz = feet.getZ();
    BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
    long seed = Long.MIN_VALUE;
    for (int dy = 0; dy <= 3; dy++) {
      m.set(px, py + dy, pz);
      if (level.getBlockState(m).isAir()) {
        seed = BlockPos.asLong(px, py + dy, pz);
        break;
      }
    }
    if (seed == Long.MIN_VALUE) {
      return "[BorderBake] no air cell near player (" + px + "," + py + "," + pz + ")";
    }
    if (!inBox(BlockPos.getX(seed), BlockPos.getY(seed), BlockPos.getZ(seed))) {
      return "[BorderBake] player is outside the dome bbox — stand inside the dome and retry";
    }

    LongOpenHashSet visited = new LongOpenHashSet();
    ArrayDeque<Long> queue = new ArrayDeque<>();
    visited.add(seed);
    queue.add(seed);

    LongOpenHashSet faceKeys = new LongOpenHashSet();
    List<int[]> faces = new ArrayList<>();
    Map<String, Integer> kept = new TreeMap<>();
    Map<String, Integer> skipped = new TreeMap<>();
    boolean leaked = false;

    while (!queue.isEmpty()) {
      if (visited.size() > MAX_VISITED) {
        leaked = true;
        break;
      }
      long c = queue.poll();
      int cx = BlockPos.getX(c);
      int cy = BlockPos.getY(c);
      int cz = BlockPos.getZ(c);
      for (Direction d : DIRS) {
        int nx = cx + d.getStepX();
        int ny = cy + d.getStepY();
        int nz = cz + d.getStepZ();
        if (!inBox(nx, ny, nz)) {
          continue; // outside bbox — treated as wall, hard clip, not recorded as a face
        }
        long n = BlockPos.asLong(nx, ny, nz);
        if (visited.contains(n)) {
          continue;
        }
        m.set(nx, ny, nz);
        BlockState st = level.getBlockState(m);
        if (st.isAir()) {
          visited.add(n);
          queue.add(n);
        } else {
          // Wall block. Its exposed face points back toward the flooded air cell — opposite d.
          Direction face = d.getOpposite();
          String name = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
          // Blocks that must never carry a star: barrier/light (invisible — a star would float in
          // void) and yellow_glazed_terracotta (watertight-boundary markers — they seal the flood
          // but are not real dome wall). They still block the flood; they're just not faces.
          if (st.is(Blocks.BARRIER)
              || st.is(Blocks.LIGHT)
              || st.is(Blocks.YELLOW_GLAZED_TERRACOTTA)) {
            skipped.merge(name, 1, Integer::sum);
            continue;
          }
          if (faceKeys.add(faceKey(nx, ny, nz, face))) {
            faces.add(new int[] {nx, ny, nz, face.ordinal()});
            kept.merge(name, 1, Integer::sum);
          }
        }
      }
    }

    // Deterministic order, matching bake-borders.py.
    faces.sort(
        Comparator.<int[]>comparingInt(f -> f[0])
            .thenComparingInt(f -> f[1])
            .thenComparingInt(f -> f[2])
            .thenComparingInt(f -> f[3]));

    try {
      writeBorders(faces);
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.error("[BorderBake] failed to write {}", OUT_PATH, e);
      return "[BorderBake] flood OK ("
          + faces.size()
          + " faces) but write failed: "
          + e.getMessage();
    }

    StringBuilder sb = new StringBuilder();
    sb.append("[BorderBake] flooded ")
        .append(visited.size())
        .append(" air cells, ")
        .append(faces.size())
        .append(" visible wall faces");
    if (leaked) {
      sb.append("  ** LEAKED past ")
          .append(MAX_VISITED)
          .append(" cells — dome has an opening, result is incomplete");
    }
    sb.append("\n  wrote ").append(OUT_PATH);
    sb.append("\n  kept materials: ").append(topN(kept));
    if (!skipped.isEmpty()) {
      sb.append("\n  skipped (markers/invisible): ").append(topN(skipped));
    }
    NotRidingAlertClient.LOGGER.info(sb.toString());
    return sb.toString();
  }

  private static boolean inBox(int x, int y, int z) {
    return x >= MIN_X && x <= MAX_X && y >= MIN_Y && y <= MAX_Y && z >= MIN_Z && z <= MAX_Z;
  }

  /** Pack (x,y,z,dir) into a unique long for face dedup — coords are bbox-bounded. */
  private static long faceKey(int x, int y, int z, Direction face) {
    long ox = x - MIN_X; // 0..130
    long oy = y - MIN_Y; // 0..70
    long oz = z - MIN_Z; // 0..130
    return ox | (oy << 8) | (oz << 16) | ((long) face.ordinal() << 24);
  }

  private static void writeBorders(List<int[]> faces) throws IOException {
    Files.createDirectories(OUT_PATH.getParent());
    try (OutputStream os = Files.newOutputStream(OUT_PATH);
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(os))) {
      out.writeBytes("IFDB"); // 4-byte magic
      out.writeByte(1); // version
      out.writeInt(faces.size());
      for (int[] f : faces) {
        out.writeInt(f[0]);
        out.writeInt(f[1]);
        out.writeInt(f[2]);
        out.writeByte(f[3]);
      }
    }
  }

  private static String topN(Map<String, Integer> counts) {
    return counts.entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
        .limit(8)
        .map(e -> e.getKey() + "=" + e.getValue())
        .reduce((a, b) -> a + ", " + b)
        .orElse("(none)");
  }
}
