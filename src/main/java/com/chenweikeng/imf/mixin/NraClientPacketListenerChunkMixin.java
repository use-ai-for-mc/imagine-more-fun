package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.spacemountain.SpaceMountainAnimationRecorder;
import com.chenweikeng.imf.nra.spacemountain.SpaceMountainBlockOverride;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hijacks chunk-arrival packets so the Space Mountain dome cells are rewritten before any renderer
 * (vanilla, Sodium, Iris) ever sees them. Rewriting the chunk's stored block states — instead of
 * intercepting per-cell {@code getBlockState} calls — is the only approach that survives Sodium's
 * mesh-builder, which reads {@code LevelChunkSection} palettes directly and bypasses both {@code
 * Level.getBlockState} and {@code LevelChunk.getBlockState}.
 *
 * <p>All three handlers run on the client packet-processor thread (same thread as {@code
 * Minecraft#tick}), so {@code chunk.setBlockState} and {@code levelRenderer.setSectionDirty} are
 * safe to call from {@code @At("TAIL")}.
 */
@Mixin(ClientPacketListener.class)
public class NraClientPacketListenerChunkMixin {
  @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
  private void imf$sealOnChunkLoad(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
    ClientLevel level = Minecraft.getInstance().level;
    if (level == null) return;
    LevelChunk chunk = level.getChunkSource().getChunkNow(packet.getX(), packet.getZ());
    if (chunk != null) SpaceMountainBlockOverride.sealChunk(chunk);
  }

  @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
  private void imf$sealOnBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
    SpaceMountainBlockOverride.sealCellIfNeeded(packet.getPos());
    if (SpaceMountainAnimationRecorder.isRecording()) {
      SpaceMountainAnimationRecorder.recordBlockUpdate(packet.getPos(), packet.getBlockState());
    }
  }

  @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
  private void imf$sealOnSectionBlocksUpdate(
      ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
    packet.runUpdates(
        (BlockPos pos, net.minecraft.world.level.block.state.BlockState state) -> {
          SpaceMountainBlockOverride.sealCellIfNeeded(pos);
          if (SpaceMountainAnimationRecorder.isRecording()) {
            SpaceMountainAnimationRecorder.recordSectionUpdate(pos, state);
          }
        });
  }
}
