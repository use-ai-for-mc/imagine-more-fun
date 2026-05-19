package com.chenweikeng.imf.nra.spacemountain;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Plugs the Space Mountain launch-tunnel entry with black concrete once the red tunnel effect
 * begins, so a glance back down the cover shows a black wall instead of the real entry tunnel.
 *
 * <p>The cells are the entry-mouth cross-section at {@code Z=147} — a near-solid 6x6 wall, {@code X
 * -260..-255} x {@code Y 66..71}, hand-marked in the SP simulator world. Like {@link
 * SpaceMountainBlockOverride} this is a client-side block-state swap — the server keeps the
 * authoritative blocks, so collision and pathing are unaffected — and originals are restored when
 * the ride gate flips off.
 *
 * <p>Gating: the seal latches on once {@link SpaceMountainOverride#isActive()} is true and the red
 * tunnel effect has started ({@link SpaceMountainTunnelRenderer#isRedPhaseStarted}) — by then the
 * rider is well clear of the entrance; it then holds for the rest of the ride. It re-applies every
 * tick, so a chunk the server re-streams is re-covered on the next tick. The {@code isActive()}
 * gate covers both Space Mountain and Hyperspace Mountain — the same physical building — so the
 * seal intentionally applies to the Hyperspace overlay as well.
 */
public final class SpaceMountainEntryTunnelSeal {
  private static final BlockState COVER = Blocks.BLACK_CONCRETE.defaultBlockState();

  // Entry-mouth cells — the Z=147 cross-section, hand-marked in the SP world. A near-solid 6x6
  // wall, X -260..-255 x Y 66..71, minus the six cells the markers skipped (most of the bottom
  // row and the top-east corner).
  private static final int ENTRY_Z = 147;
  private static final int[][] ENTRY_XY = {
    {-260, 66},
    {-260, 67},
    {-260, 68},
    {-260, 69},
    {-260, 70},
    {-260, 71},
    {-259, 67},
    {-259, 68},
    {-259, 69},
    {-259, 70},
    {-259, 71},
    {-258, 67},
    {-258, 68},
    {-258, 69},
    {-258, 70},
    {-258, 71},
    {-257, 67},
    {-257, 68},
    {-257, 69},
    {-257, 70},
    {-257, 71},
    {-256, 67},
    {-256, 68},
    {-256, 69},
    {-256, 70},
    {-256, 71},
    {-255, 67},
    {-255, 68},
    {-255, 69},
    {-255, 70},
  };
  private static final BlockPos[] CELLS = new BlockPos[ENTRY_XY.length];

  static {
    for (int i = 0; i < ENTRY_XY.length; i++) {
      CELLS[i] = new BlockPos(ENTRY_XY[i][0], ENTRY_XY[i][1], ENTRY_Z);
    }
  }

  // Pre-cover block state of every cell we've replaced — restored when the gate flips off.
  private static final Map<BlockPos, BlockState> originalStates = new HashMap<>();
  // Latches true once the red tunnel effect starts; reset when the ride gate flips off.
  private static boolean entered = false;
  // Whether the cover is currently applied (drives the one-time full re-mesh).
  private static boolean sealed = false;

  private SpaceMountainEntryTunnelSeal() {}

  public static void init() {
    ClientTickEvents.END_CLIENT_TICK.register(SpaceMountainEntryTunnelSeal::onTick);
    ClientPlayConnectionEvents.DISCONNECT.register(
        (handler, client) -> {
          originalStates.clear();
          entered = false;
          sealed = false;
        });
  }

  private static void onTick(Minecraft mc) {
    if (mc.level == null || mc.player == null || mc.levelRenderer == null) return;

    if (!SpaceMountainOverride.isActive()) {
      if (sealed) restore(mc);
      entered = false;
      return;
    }
    if (!entered && SpaceMountainTunnelRenderer.isRedPhaseStarted()) {
      entered = true;
    }
    if (entered) applySeal(mc);
  }

  /**
   * Force every entry-mouth cell to black concrete. Idempotent — cells already covered are skipped
   * — so calling it each tick cheaply re-covers any chunk the server has re-streamed.
   */
  private static void applySeal(Minecraft mc) {
    ClientChunkCache src = mc.level.getChunkSource();
    Set<Long> dirty = new HashSet<>();
    for (BlockPos cell : CELLS) {
      LevelChunk chunk = src.getChunkNow(cell.getX() >> 4, cell.getZ() >> 4);
      if (chunk == null) continue;
      BlockState before = chunk.getBlockState(cell);
      if (before.equals(COVER)) continue;
      originalStates.putIfAbsent(cell, before);
      chunk.setBlockState(cell, COVER, 0);
      dirty.add(SectionPos.asLong(cell.getX() >> 4, cell.getY() >> 4, cell.getZ() >> 4));
    }
    if (dirty.isEmpty()) return; // nothing changed this tick

    for (long key : dirty) {
      mc.levelRenderer.setSectionDirty(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
    }
    if (!sealed) {
      // First application: the entry-tunnel chunk was meshed as the rider launched past it, and a
      // bare setSectionDirty isn't reliable then (see SpaceMountainBlockOverride) — force a full
      // re-mesh once so the cover actually shows.
      sealed = true;
      mc.levelRenderer.allChanged();
      NotRidingAlertClient.LOGGER.info(
          "[SpaceMountainEntryTunnelSeal] entry tunnel sealed ({} cells)", originalStates.size());
    }
  }

  /** Restore every cell still carrying our cover; called when the ride gate flips off. */
  private static void restore(Minecraft mc) {
    ClientChunkCache src = mc.level.getChunkSource();
    for (Map.Entry<BlockPos, BlockState> e : originalStates.entrySet()) {
      BlockPos cell = e.getKey();
      LevelChunk chunk = src.getChunkNow(cell.getX() >> 4, cell.getZ() >> 4);
      if (chunk == null) continue; // chunk unloaded — nothing to restore
      // Only undo cells still carrying our cover — if something else (the dome overlay, a server
      // re-stream) already owns the cell, leave that value alone.
      if (!chunk.getBlockState(cell).equals(COVER)) continue;
      chunk.setBlockState(cell, e.getValue(), 0);
    }
    int restored = originalStates.size();
    originalStates.clear();
    sealed = false;
    mc.levelRenderer.allChanged();
    NotRidingAlertClient.LOGGER.info(
        "[SpaceMountainEntryTunnelSeal] entry tunnel restored ({} cells)", restored);
  }
}
