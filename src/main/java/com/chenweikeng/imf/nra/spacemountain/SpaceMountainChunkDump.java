package com.chenweikeng.imf.nra.spacemountain;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * In-game chunk dumper — the bridge-callable port of the retired {@code /imf dumpchunks} command.
 * Snapshots every loaded chunk within a square radius of the player to a single gzipped binary, for
 * offline overlay baking ({@code bake-overlay.py}) and other analysis.
 *
 * <p>Runs entirely on the client thread in pure Java — no Lua-bridge crossings — so it captures
 * thousands of chunk-sections in well under a second. The {@code getBlockState} flood that would
 * choke the DebugBridge is fine in-process.
 *
 * <p>Output: {@code config/imaginemorefun/chunks-<epochMs>.bin.gz}. (The retired command wrote to a
 * hardcoded developer path; this writes under the mod config dir instead.)
 *
 * <h2>File format (all big-endian, after gunzip)</h2>
 *
 * <pre>
 * Header
 *   "IMFC"          4-byte magic
 *   uint8           version = 1
 *   UTF             dimension key (DataInput.readUTF format: u16 len + UTF-8 bytes)
 *   double * 3      player x, y, z
 *   float * 2       player yRot, xRot
 *   int32           radiusChunks
 *   int32 * 4       minCx, maxCx, minCz, maxCz (inclusive)
 *   int32 * 2       minLightSectionY, maxLightSectionY
 *   int32           chunkCount
 * Per chunk
 *   int32 * 2       cx, cz
 *   int32           sectionCount
 *   Per section
 *     int32         sy
 *     uint16        paletteSize
 *     UTF * paletteSize   block-state strings (e.g. "minecraft:stone[waterlogged=false]")
 *     uint8         indexBytesPerEntry  (1 if paletteSize <= 256, else 2)
 *     uint8/16 * 4096   block indices, ordered y*256 + z*16 + x
 *     uint8         blockLightFlag  (0=absent, 1=allZeroes, 2=dataFollows)
 *     byte * 2048   blockLight (only if flag==2)
 *     uint8         skyLightFlag    (same encoding)
 *     byte * 2048   skyLight (only if flag==2)
 * </pre>
 *
 * <p>Sections that are entirely air AND carry no light data are skipped to keep the file small.
 */
public final class SpaceMountainChunkDump {
  private static final byte VERSION = 1;
  private static final int VOXELS_PER_SECTION = 16 * 16 * 16;

  private SpaceMountainChunkDump() {}

  /**
   * Dump every loaded chunk within {@code radiusChunks} of the player to {@code
   * config/imaginemorefun/chunks-<timestamp>.bin.gz}. Bridge entry point; returns a summary.
   */
  public static String dump(int radiusChunks) {
    Minecraft mc = Minecraft.getInstance();
    LocalPlayer player = mc.player;
    ClientLevel level = mc.level;
    if (player == null || level == null) {
      return "[ChunkDump] no world loaded";
    }

    long t0 = System.nanoTime();
    int pcx = (int) Math.floor(player.getX() / 16.0);
    int pcz = (int) Math.floor(player.getZ() / 16.0);
    int minCx = pcx - radiusChunks;
    int maxCx = pcx + radiusChunks;
    int minCz = pcz - radiusChunks;
    int maxCz = pcz + radiusChunks;

    LevelLightEngine le = level.getLightEngine();
    LayerLightEventListener blockEng = le.getLayerListener(LightLayer.BLOCK);
    LayerLightEventListener skyEng = le.getLayerListener(LightLayer.SKY);
    int minLightSection = le.getMinLightSection();
    int maxLightSection = le.getMaxLightSection();

    ClientChunkCache cs = level.getChunkSource();

    List<LevelChunk> loaded = new ArrayList<>();
    for (int cx = minCx; cx <= maxCx; cx++) {
      for (int cz = minCz; cz <= maxCz; cz++) {
        LevelChunk chunk = cs.getChunkNow(cx, cz);
        if (chunk != null) loaded.add(chunk);
      }
    }

    Path outPath =
        FabricLoader.getInstance()
            .getConfigDir()
            .resolve("imaginemorefun")
            .resolve("chunks-" + Instant.now().toEpochMilli() + ".bin.gz");
    int totalSections = 0;
    long fileBytes;

    try {
      Files.createDirectories(outPath.getParent());
      try (OutputStream fileOut = Files.newOutputStream(outPath);
          GZIPOutputStream gz = new GZIPOutputStream(fileOut, 64 * 1024);
          DataOutputStream out = new DataOutputStream(gz)) {

        // Header
        out.writeBytes("IMFC");
        out.writeByte(VERSION);
        out.writeUTF(level.dimension().identifier().toString());
        out.writeDouble(player.getX());
        out.writeDouble(player.getY());
        out.writeDouble(player.getZ());
        out.writeFloat(player.getYRot());
        out.writeFloat(player.getXRot());
        out.writeInt(radiusChunks);
        out.writeInt(minCx);
        out.writeInt(maxCx);
        out.writeInt(minCz);
        out.writeInt(maxCz);
        out.writeInt(minLightSection);
        out.writeInt(maxLightSection);
        out.writeInt(loaded.size());

        for (LevelChunk chunk : loaded) {
          int cx = chunk.getPos().x;
          int cz = chunk.getPos().z;
          out.writeInt(cx);
          out.writeInt(cz);

          // Buffer this chunk's sections so we know the count up-front.
          ByteArrayOutputStream sectionBuf = new ByteArrayOutputStream(64 * 1024);
          DataOutputStream sout = new DataOutputStream(sectionBuf);
          int sectionsInChunk = 0;

          int minSecY = chunk.getMinSectionY();
          LevelChunkSection[] sections = chunk.getSections();

          for (int idx = 0; idx < sections.length; idx++) {
            LevelChunkSection section = sections[idx];
            int sy = minSecY + idx;

            boolean blocksEmpty = (section == null || section.hasOnlyAir());

            // Pull light data. If block storage is empty AND both light layers are null/empty,
            // skip — the section carries no useful info.
            SectionPos sp = SectionPos.of(cx, sy, cz);
            DataLayer bdl = blockEng.getDataLayerData(sp);
            DataLayer sdl = skyEng.getDataLayerData(sp);
            int bFlag = lightFlag(bdl);
            int sFlag = lightFlag(sdl);

            if (blocksEmpty && bFlag != 2 && sFlag != 2) continue;

            sout.writeInt(sy);

            // Build palette + indices for this section.
            Object2IntOpenHashMap<BlockState> palette = new Object2IntOpenHashMap<>();
            palette.defaultReturnValue(-1);
            int[] indices = new int[VOXELS_PER_SECTION];

            if (blocksEmpty) {
              palette.put(Blocks.AIR.defaultBlockState(), 0);
            } else {
              for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                  for (int x = 0; x < 16; x++) {
                    BlockState bs = section.getBlockState(x, y, z);
                    int p = palette.getInt(bs);
                    if (p == -1) {
                      p = palette.size();
                      palette.put(bs, p);
                    }
                    indices[y * 256 + z * 16 + x] = p;
                  }
                }
              }
            }

            int paletteSize = palette.size();
            sout.writeShort(paletteSize);
            BlockState[] paletteArr = new BlockState[paletteSize];
            palette.object2IntEntrySet().fastForEach(e -> paletteArr[e.getIntValue()] = e.getKey());
            for (BlockState bs : paletteArr) {
              sout.writeUTF(BlockStateParser.serialize(bs));
            }

            if (paletteSize <= 256) {
              sout.writeByte(1);
              for (int i = 0; i < VOXELS_PER_SECTION; i++) sout.writeByte(indices[i]);
            } else {
              sout.writeByte(2);
              for (int i = 0; i < VOXELS_PER_SECTION; i++) sout.writeShort(indices[i]);
            }

            sout.writeByte(bFlag);
            if (bFlag == 2) sout.write(bdl.getData());
            sout.writeByte(sFlag);
            if (sFlag == 2) sout.write(sdl.getData());

            sectionsInChunk++;
          }

          out.writeInt(sectionsInChunk);
          sectionBuf.writeTo(out);
          totalSections += sectionsInChunk;
        }

        out.flush();
      }
      fileBytes = Files.size(outPath);
    } catch (IOException e) {
      NotRidingAlertClient.LOGGER.error("[ChunkDump] dump failed", e);
      return "[ChunkDump] failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
    String msg =
        String.format(
            "[ChunkDump] dumped %d chunks (%d sections) in %d ms -> %s (%d KB gz)",
            loaded.size(), totalSections, elapsedMs, outPath, fileBytes / 1024);
    NotRidingAlertClient.LOGGER.info(msg);
    return msg;
  }

  private static int lightFlag(DataLayer dl) {
    if (dl == null) return 0;
    if (dl.isEmpty()) return 1;
    return 2;
  }
}
