package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.skincache.prewarm.ChunkHeadScanner;
import com.chenweikeng.imf.skincache.prewarm.PrewarmRegistry;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into ClientChunkCache to pre-warm player head skins when chunks load, and clean up tracking
 * when chunks unload.
 *
 * <p>Only targets player head blocks (SkullBlockEntity), not player entity skins.
 */
@Mixin(ClientChunkCache.class)
public abstract class SkinCacheClientChunkCacheMixin {

  @Shadow @Final private ClientLevel level;

  /**
   * When a chunk finishes loading from the server, scan it for player head block entities and kick
   * off skin loading early.
   */
  @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
  private void skincache$onChunkLoad(
      int chunkX,
      int chunkZ,
      FriendlyByteBuf readBuffer,
      Map<Heightmap.Types, long[]> heightmaps,
      Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities,
      CallbackInfoReturnable<LevelChunk> cir) {
    LevelChunk chunk = cir.getReturnValue();
    if (chunk == null) return;

    List<ResolvableProfile> profiles = ChunkHeadScanner.findPlayerHeadProfiles(chunk);
    if (profiles.isEmpty()) return;

    var skinRenderCache = Minecraft.getInstance().playerSkinRenderCache();
    if (skinRenderCache == null) return;

    String worldName = this.level.dimension().identifier().toString();
    PrewarmRegistry.prewarmChunk(worldName, chunkX, chunkZ, profiles, skinRenderCache);
  }

  /** When a chunk unloads, clean up pre-warm tracking. */
  @Inject(method = "drop(Lnet/minecraft/world/level/ChunkPos;)V", at = @At("HEAD"))
  private void skincache$onChunkUnload(ChunkPos pos, CallbackInfo ci) {
    String worldName = this.level.dimension().identifier().toString();
    PrewarmRegistry.invalidateChunk(worldName, pos.x(), pos.z());
  }
}
