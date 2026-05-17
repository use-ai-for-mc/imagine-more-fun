package com.chenweikeng.imf.nra.spacemountain;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Client-side block-state replacement applied while the player is riding Space or Hyperspace
 * Mountain. Reads a single overlay binary ({@code dome_overlay.bin}) — a diff between the live
 * ImagineFun world and the curated SP simulator world — and forces every overlay cell to its SP
 * target state while the ride gate is active. Originals are restored when the gate flips off.
 *
 * <p>The overlay collapses what used to be four separate runtime layers (PeopleMover bbox seal,
 * explicit cover positions, animation-suppress, knockout patch) into one offline-baked diff. To
 * change what the ride looks like, edit the SP simulator world, re-dump live + sp, and re-run
 * {@code debug-dumps/bake-overlay.py}.
 *
 * <p>Chunk meshes are cached per section, so changes to stored block states don't repaint the world
 * automatically. {@link #init} watches the active flag every tick and forces a full re-mesh on
 * transition. After the initial repaint, ordinary chunk updates pick up further changes naturally.
 */
public final class SpaceMountainBlockOverride {
  private static final String OVERLAY_RESOURCE = "/imaginemorefun/dome_overlay.bin";

  private record OverlayEntry(BlockPos pos, String stateString) {}

  private static final List<OverlayEntry> rawOverlayEntries = loadOverlay();

  // Lazy: parsed only after mc.level is available (BlockStateParser needs HolderLookup<Block>).
  // Indexed by chunk key for O(1) per-chunk lookup in the seal hot path.
  private static Map<Long, List<Map.Entry<BlockPos, BlockState>>> overlayByChunk;

  // Cells we have replaced, keyed by world pos. When the gate flips false (e.g. player leaves
  // Space Mountain and boards PeopleMover, which passes through the same dome), each cell is
  // restored to its original BlockState — the client sees the chunk "update" as if from the
  // server.
  private static final Map<BlockPos, BlockState> originalStates = new HashMap<>();

  private static boolean previousActive = false;
  private static boolean pendingRemesh = false;

  private SpaceMountainBlockOverride() {}

  public static void init() {
    ClientTickEvents.END_CLIENT_TICK.register(SpaceMountainBlockOverride::onTick);
    ClientPlayConnectionEvents.DISCONNECT.register(
        (handler, client) -> {
          previousActive = false;
          pendingRemesh = false;
          originalStates.clear();
        });
  }

  private static List<OverlayEntry> loadOverlay() {
    List<OverlayEntry> out = new ArrayList<>();
    try (InputStream in = SpaceMountainBlockOverride.class.getResourceAsStream(OVERLAY_RESOURCE)) {
      if (in == null) return out;
      DataInputStream dis = new DataInputStream(in);
      byte[] magic = new byte[4];
      dis.readFully(magic);
      if (magic[0] != 'I' || magic[1] != 'F' || magic[2] != 'O' || magic[3] != 'V') {
        throw new IOException("bad overlay magic: " + new String(magic));
      }
      int version = dis.readUnsignedByte();
      if (version != 1) throw new IOException("unsupported overlay version: " + version);
      int count = dis.readInt();
      for (int i = 0; i < count; i++) {
        int x = dis.readInt();
        int y = dis.readInt();
        int z = dis.readInt();
        String state = dis.readUTF();
        out.add(new OverlayEntry(new BlockPos(x, y, z), state));
      }
      NotRidingAlertClient.LOGGER.info(
          "[SpaceMountainBlockOverride] loaded {} overlay entries", count);
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.error("[SpaceMountainBlockOverride] failed to load overlay", e);
    }
    return out;
  }

  private static long chunkKey(int cx, int cz) {
    return ((long) cx << 32) | (cz & 0xffffffffL);
  }

  private static void ensureOverlayIndex(Minecraft mc) {
    if (overlayByChunk != null) return;
    if (mc.level == null) return;
    if (rawOverlayEntries.isEmpty()) {
      overlayByChunk = Map.of();
      return;
    }
    HolderLookup<Block> blocks = mc.level.registryAccess().lookupOrThrow(Registries.BLOCK);
    Map<Long, List<Map.Entry<BlockPos, BlockState>>> idx = new HashMap<>();
    int parsed = 0;
    int failed = 0;
    for (OverlayEntry e : rawOverlayEntries) {
      try {
        BlockStateParser.BlockResult r =
            BlockStateParser.parseForBlock(blocks, e.stateString, false);
        long key = chunkKey(e.pos.getX() >> 4, e.pos.getZ() >> 4);
        idx.computeIfAbsent(key, k -> new ArrayList<>()).add(Map.entry(e.pos, r.blockState()));
        parsed++;
      } catch (CommandSyntaxException ex) {
        failed++;
        if (failed <= 5) {
          NotRidingAlertClient.LOGGER.warn(
              "[SpaceMountainBlockOverride] failed to parse overlay state '{}': {}",
              e.stateString,
              ex.getMessage());
        }
      }
    }
    overlayByChunk = idx;
    NotRidingAlertClient.LOGGER.info(
        "[SpaceMountainBlockOverride] overlay index built: {} parsed / {} failed across {} chunks",
        parsed,
        failed,
        idx.size());
  }

  // Race conditions handled:
  // 1. levelRenderer/level null at activation → don't latch previousActive, retry next tick.
  // 2. Chunks not yet loaded when gate flips → arriving chunks pick up the overlay via
  //    handleLevelChunkWithLight mixin → sealChunk.
  // 3. DISCONNECT clears state so a fresh ride entry on rejoin re-seals.
  // 4. Gate flip true→false restores originals so the rider sees the real wall.
  private static void onTick(Minecraft mc) {
    if (mc.levelRenderer == null || mc.level == null) return;

    boolean active = SpaceMountainOverride.isActive();

    if (active != previousActive) {
      previousActive = active;
      if (active) {
        pendingRemesh = true;
      } else {
        desealAll(mc);
        NotRidingAlertClient.LOGGER.info(
            "[SpaceMountainBlockOverride] inactive → restored {} cells", originalStates.size());
      }
    }

    if (pendingRemesh && active) {
      sealAllLoadedChunks(mc);
      pendingRemesh = false;
      NotRidingAlertClient.LOGGER.info(
          "[SpaceMountainBlockOverride] active → sealed loaded chunks ({} cells)",
          originalStates.size());
    }
  }

  /** Walks every chunk in the overlay index and seals whichever ones are currently loaded. */
  public static void sealAllLoadedChunks(Minecraft mc) {
    if (mc.level == null) return;
    ensureOverlayIndex(mc);
    if (overlayByChunk == null || overlayByChunk.isEmpty()) return;
    ClientChunkCache src = mc.level.getChunkSource();
    for (long key : overlayByChunk.keySet()) {
      int cx = (int) (key >> 32);
      int cz = (int) key;
      LevelChunk chunk = src.getChunkNow(cx, cz);
      if (chunk != null) sealChunk(chunk);
    }
  }

  /**
   * Apply every overlay entry for this chunk. Idempotent — cells already at their target state are
   * skipped. Triggers a Sodium-aware re-mesh via {@code setSectionDirty} on each affected section.
   *
   * <p>Called from the chunk-load packet handler ({@code handleLevelChunkWithLight}) and from the
   * activation tick. Only runs when {@link SpaceMountainOverride#isActive()}.
   */
  public static void sealChunk(LevelChunk chunk) {
    if (!SpaceMountainOverride.isActive()) return;
    Minecraft mc = Minecraft.getInstance();
    ensureOverlayIndex(mc);
    if (overlayByChunk == null) return;
    List<Map.Entry<BlockPos, BlockState>> entries =
        overlayByChunk.get(chunkKey(chunk.getPos().x, chunk.getPos().z));
    if (entries == null || entries.isEmpty()) return;

    Set<Long> dirtySections = new HashSet<>();
    for (Map.Entry<BlockPos, BlockState> entry : entries) {
      BlockPos p = entry.getKey();
      BlockState target = entry.getValue();
      BlockState before = chunk.getBlockState(p);
      if (before.equals(target)) continue;
      originalStates.putIfAbsent(p, before);
      chunk.setBlockState(p, target, 0);
      dirtySections.add(SectionPos.asLong(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4));
    }

    if (mc.levelRenderer != null) {
      for (long key : dirtySections) {
        mc.levelRenderer.setSectionDirty(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
      }
    }
  }

  /** Re-apply the overlay for a single cell touched by an incremental block update. */
  public static void sealCellIfNeeded(BlockPos pos) {
    if (!SpaceMountainOverride.isActive()) return;
    Minecraft mc = Minecraft.getInstance();
    if (mc.level == null) return;
    ensureOverlayIndex(mc);
    if (overlayByChunk == null) return;
    List<Map.Entry<BlockPos, BlockState>> chunkEntries =
        overlayByChunk.get(chunkKey(pos.getX() >> 4, pos.getZ() >> 4));
    if (chunkEntries == null) return;
    BlockState target = null;
    for (Map.Entry<BlockPos, BlockState> e : chunkEntries) {
      if (e.getKey().equals(pos)) {
        target = e.getValue();
        break;
      }
    }
    if (target == null) return;

    LevelChunk chunk = mc.level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
    if (chunk == null) return;
    BlockState before = chunk.getBlockState(pos);
    if (before.equals(target)) return;
    BlockPos posKey = pos.immutable();
    originalStates.putIfAbsent(posKey, before);
    chunk.setBlockState(posKey, target, 0);
    if (mc.levelRenderer != null) {
      mc.levelRenderer.setSectionDirty(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }
  }

  /**
   * Restore every cell we previously replaced, then clear the tracking map. Triggers a per- section
   * re-mesh so the rider sees the original blocks again — feels like a server-side chunk update.
   */
  private static void desealAll(Minecraft mc) {
    if (originalStates.isEmpty()) return;
    if (mc.level == null) {
      originalStates.clear();
      return;
    }
    ClientChunkCache src = mc.level.getChunkSource();
    Set<Long> dirtySections = new HashSet<>();
    for (Map.Entry<BlockPos, BlockState> e : originalStates.entrySet()) {
      BlockPos pos = e.getKey();
      LevelChunk chunk = src.getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
      if (chunk == null) continue; // chunk unloaded; nothing to restore
      chunk.setBlockState(pos, e.getValue(), 0);
      dirtySections.add(SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
    }
    if (mc.levelRenderer != null) {
      for (long key : dirtySections) {
        mc.levelRenderer.setSectionDirty(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
      }
    }
    originalStates.clear();
  }
}
