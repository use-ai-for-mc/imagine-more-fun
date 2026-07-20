package com.chenweikeng.imf.skincache.prewarm;

import com.chenweikeng.imf.skincache.SkinCacheMod;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * Pre-warms player head skins when chunks load.
 *
 * <p>Strategy: 1. For each skull profile in the chunk, check if we have BOTH: - ProfileCache entry
 * (UUID → texture URL + hash + texture ID path) - TextureCache entry (URL → PNG bytes on disk) 2.
 * If yes: register it through {@link TextureRegistrar}, whose process-wide registry guarantees that
 * a texture is read, decoded, and GPU-uploaded at most once. 3. Then call lookup() to populate
 * PlayerSkinRenderCache. 4. If no complete cache hit exists, fall back to async lookup() (original
 * behavior).
 */
public final class PrewarmRegistry {

  private static final ConcurrentHashMap<String, Set<ResolvableProfile>> chunkProfiles =
      new ConcurrentHashMap<>();

  private PrewarmRegistry() {}

  /**
   * Pre-warm skull profiles from a loaded chunk. Called on the main thread from
   * ClientChunkCacheMixin.
   */
  public static void prewarmChunk(
      String worldName,
      int chunkX,
      int chunkZ,
      java.util.List<ResolvableProfile> profiles,
      PlayerSkinRenderCache skinRenderCache) {
    if (profiles.isEmpty()) return;

    String key = chunkKey(worldName, chunkX, chunkZ);
    Set<ResolvableProfile> set = ConcurrentHashMap.newKeySet();
    set.addAll(profiles);
    chunkProfiles.put(key, set);

    int syncCount = 0;
    int asyncCount = 0;

    for (ResolvableProfile profile : profiles) {
      UUID uuid = profile.partialProfile().id();
      if (uuid == null) {
        // No UUID — can't look up profile cache, fall back to async
        skinRenderCache.lookup(profile);
        asyncCount++;
        continue;
      }

      ProfileCache.ProfileEntry entry = ProfileCache.get(uuid.toString());
      if (entry == null) {
        // No cached profile — async
        skinRenderCache.lookup(profile);
        asyncCount++;
        continue;
      }

      Identifier textureId = Identifier.withDefaultNamespace(entry.textureIdPath);
      if (!TextureRegistrar.ensureRegistered(textureId, entry.textureUrl)) {
        skinRenderCache.lookup(profile);
        asyncCount++;
        continue;
      }

      // Kick off the lookup — since the texture is now registered, the async chain
      // should complete very quickly (profile resolve uses our cached data,
      // downloadSkin hits our cache, registerTextureInManager finds it already registered)
      skinRenderCache.lookup(profile);
      syncCount++;
    }

    if (syncCount > 0 || asyncCount > 0) {
      SkinCacheMod.LOGGER.debug(
          "[SkinCache] Pre-warmed chunk [{}, {}]: {} sync, {} async",
          chunkX,
          chunkZ,
          syncCount,
          asyncCount);
    }
  }

  public static void invalidateChunk(String worldName, int chunkX, int chunkZ) {
    chunkProfiles.remove(chunkKey(worldName, chunkX, chunkZ));
  }

  public static void clear() {
    chunkProfiles.clear();
  }

  private static String chunkKey(String worldName, int chunkX, int chunkZ) {
    return worldName + ":" + chunkX + "," + chunkZ;
  }
}
