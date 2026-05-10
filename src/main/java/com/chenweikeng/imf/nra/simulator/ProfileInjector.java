package com.chenweikeng.imf.nra.simulator;

import com.chenweikeng.imf.ImfClient;
import com.chenweikeng.imf.mixin.NraClientPacketListenerAccessor;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

/**
 * Adds and removes synthetic {@link PlayerInfo} entries on the active {@link
 * ClientPacketListener#playerInfoMap} so the standard skin pipeline ({@code
 * AbstractClientPlayer#getSkin} → {@code SkinManager}) works for our ghost players without having
 * to mixin into the renderer.
 */
public final class ProfileInjector {

  private ProfileInjector() {}

  public static boolean inject(GhostSpec spec) {
    Map<UUID, PlayerInfo> map = playerInfoMap();
    if (map == null) {
      ImfClient.LOGGER.warn("ProfileInjector: no active connection; cannot inject {}", spec.name());
      return false;
    }
    if (map.containsKey(spec.uuid())) {
      ImfClient.LOGGER.debug("ProfileInjector: {} already present; skipping", spec.name());
      return false;
    }

    // Modern authlib makes GameProfile's PropertyMap effectively immutable once constructed,
    // so we have to populate the multimap *before* wrapping it. Mutating
    // profile.properties().put(...) after construction throws UnsupportedOperationException.
    GameProfile profile;
    if (spec.texturesValue() != null) {
      Multimap<String, Property> mm = LinkedListMultimap.create();
      mm.put("textures", new Property("textures", spec.texturesValue(), spec.texturesSignature()));
      profile = new GameProfile(spec.uuid(), spec.name(), new PropertyMap(mm));
    } else {
      profile = new GameProfile(spec.uuid(), spec.name());
    }

    PlayerInfo info = new PlayerInfo(profile, false);
    map.put(spec.uuid(), info);
    ImfClient.LOGGER.info("ProfileInjector: injected {} ({})", spec.name(), spec.uuid());
    return true;
  }

  public static boolean remove(UUID uuid) {
    Map<UUID, PlayerInfo> map = playerInfoMap();
    if (map == null) return false;
    PlayerInfo removed = map.remove(uuid);
    if (removed != null) {
      ImfClient.LOGGER.info("ProfileInjector: removed {} ({})", removed.getProfile().name(), uuid);
      return true;
    }
    return false;
  }

  public static boolean isInjected(UUID uuid) {
    Map<UUID, PlayerInfo> map = playerInfoMap();
    return map != null && map.containsKey(uuid);
  }

  private static Map<UUID, PlayerInfo> playerInfoMap() {
    ClientPacketListener listener = Minecraft.getInstance().getConnection();
    if (listener == null) return null;
    return ((NraClientPacketListenerAccessor) listener).getPlayerInfoMap();
  }
}
