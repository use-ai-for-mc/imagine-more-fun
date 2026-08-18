package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.skincache.SkinCacheMod;
import com.chenweikeng.imf.skincache.prewarm.ProfileCache;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures resolved skin profiles (UUID → texture URL/hash) for the profile cache. */
@Mixin(SkinManager.class)
public abstract class SkinCacheSkinManagerMixin {

  /**
   * After registerTextures resolves a profile's textures, capture the mapping UUID → texture URL +
   * hash into our ProfileCache for future synchronous lookups.
   */
  @Inject(method = "registerTextures", at = @At("HEAD"))
  private void skincache$captureProfile(
      UUID profileId,
      MinecraftProfileTextures textures,
      CallbackInfoReturnable<CompletableFuture<PlayerSkin>> cir) {
    try {
      MinecraftProfileTexture skinInfo = textures.skin();
      if (skinInfo != null) {
        String url = skinInfo.getUrl();
        String hash = skinInfo.getHash();
        ProfileCache.put(profileId.toString(), url, hash);
        SkinCacheMod.LOGGER.debug("[SkinCache] Captured profile {} → hash={}", profileId, hash);
      }
    } catch (Exception e) {
      SkinCacheMod.LOGGER.warn("[SkinCache] Failed to capture profile texture info", e);
    }
  }
}
