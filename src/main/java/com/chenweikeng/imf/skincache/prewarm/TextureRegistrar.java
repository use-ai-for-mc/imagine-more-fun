package com.chenweikeng.imf.skincache.prewarm;

import com.chenweikeng.imf.skincache.SkinCacheMod;
import com.chenweikeng.imf.skincache.cache.TextureCache;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

/**
 * Ensures a skin texture is registered with TextureManager (GPU-uploaded) before returning a
 * RenderType referencing it. Shared by all render-path mixins.
 */
public final class TextureRegistrar {

  // Track which texture IDs we've already registered to avoid redundant work
  private static final ConcurrentHashMap<String, Boolean> registered = new ConcurrentHashMap<>();

  private TextureRegistrar() {}

  /**
   * Bulk pre-register all textures that have both a ProfileCache entry and a TextureCache entry.
   * Call on the main/render thread when GPU is ready.
   */
  public static void registerAll() {
    long t0 = System.nanoTime();
    int success = 0;
    int skipped = 0;
    int failed = 0;

    for (var entry : ProfileCache.allEntries()) {
      if (!TextureCache.isCached(entry.textureUrl)) {
        skipped++;
        continue;
      }

      Identifier textureId = Identifier.withDefaultNamespace(entry.textureIdPath);
      if (ensureRegistered(textureId, entry.textureUrl)) {
        success++;
      } else {
        failed++;
      }
    }

    long elapsed = (System.nanoTime() - t0) / 1_000_000;
    SkinCacheMod.LOGGER.debug(
        "[SkinCache] Bulk texture registration: {} registered, {} skipped, {} failed in {}ms",
        success,
        skipped,
        failed,
        elapsed);
  }

  /**
   * Ensure the texture is registered with TextureManager. Returns true if the texture is ready to
   * render, false if registration failed.
   */
  public static boolean ensureRegistered(Identifier textureId, String textureUrl) {
    String idStr = textureId.toString();

    // Fast path: already registered by us
    if (registered.containsKey(idStr)) return true;

    TextureManager textureManager = Minecraft.getInstance().getTextureManager();

    // Register the texture from our cache
    Optional<Path> cachedPng = TextureCache.get(textureUrl);
    if (cachedPng.isEmpty()) return false;

    try {
      byte[] pngData = Files.readAllBytes(cachedPng.get());
      NativeImage image = NativeImage.read(pngData);
      image = processLegacySkinIfNeeded(image);
      DynamicTexture texture = new DynamicTexture(textureId::toString, image);
      textureManager.register(textureId, texture);
      registered.put(idStr, Boolean.TRUE);
      return true;
    } catch (Exception e) {
      SkinCacheMod.LOGGER.warn("[SkinCache] Failed to register texture {}", idStr, e);
      return false;
    }
  }

  private static NativeImage processLegacySkinIfNeeded(NativeImage image) {
    int w = image.getWidth();
    int h = image.getHeight();
    if (w == 64 && h == 32) {
      NativeImage newImage = new NativeImage(64, 64, true);
      newImage.copyFrom(image);
      image.close();
      image = newImage;
      image.fillRect(0, 32, 64, 32, 0);
      image.copyRect(4, 16, 16, 32, 4, 4, true, false);
      image.copyRect(8, 16, 16, 32, 4, 4, true, false);
      image.copyRect(0, 20, 24, 32, 4, 12, true, false);
      image.copyRect(4, 20, 16, 32, 4, 12, true, false);
      image.copyRect(8, 20, 8, 32, 4, 12, true, false);
      image.copyRect(12, 20, 16, 32, 4, 12, true, false);
      image.copyRect(44, 16, -8, 32, 4, 4, true, false);
      image.copyRect(48, 16, -8, 32, 4, 4, true, false);
      image.copyRect(40, 20, 0, 32, 4, 12, true, false);
      image.copyRect(44, 20, -8, 32, 4, 12, true, false);
      image.copyRect(48, 20, -16, 32, 4, 12, true, false);
      image.copyRect(52, 20, -8, 32, 4, 12, true, false);
    }
    return image;
  }
}
