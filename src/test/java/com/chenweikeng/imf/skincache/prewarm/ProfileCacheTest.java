package com.chenweikeng.imf.skincache.prewarm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProfileCacheTest {

  @Test
  void identicalResolvedProfileIsANoOp() {
    ProfileCache.ProfileEntry entry = entry();

    assertTrue(
        ProfileCache.matches(
            entry, entry.uuid, entry.textureUrl, entry.textureHash, entry.textureIdPath));
  }

  @Test
  void changedTextureRequiresPersistence() {
    ProfileCache.ProfileEntry entry = entry();

    assertFalse(
        ProfileCache.matches(
            entry,
            entry.uuid,
            "https://textures.minecraft.net/texture/new-hash",
            "new-hash",
            "skins/new-id"));
  }

  @Test
  void incompleteLegacyEntryIsRepaired() {
    ProfileCache.ProfileEntry entry = entry();
    entry.textureIdPath = null;

    assertFalse(
        ProfileCache.matches(
            entry, entry.uuid, entry.textureUrl, entry.textureHash, "skins/texture-id"));
  }

  private static ProfileCache.ProfileEntry entry() {
    ProfileCache.ProfileEntry entry = new ProfileCache.ProfileEntry();
    entry.uuid = "00000000-0000-0000-0000-000000000001";
    entry.textureUrl = "https://textures.minecraft.net/texture/texture-hash";
    entry.textureHash = "texture-hash";
    entry.textureIdPath = "skins/texture-id";
    entry.timestamp = 1;
    entry.lastAccessed = 2;
    return entry;
  }
}
