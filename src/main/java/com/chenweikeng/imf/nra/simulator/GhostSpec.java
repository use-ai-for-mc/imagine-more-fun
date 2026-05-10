package com.chenweikeng.imf.nra.simulator;

import java.util.Map;
import java.util.UUID;

/**
 * Frozen description of a ghost passenger we can spawn.
 *
 * @param texturesValue Base64-encoded value of the Mojang "textures" property — i.e. the JSON
 *     payload returned by the session server, base64'd. Embedded directly in the GameProfile.
 * @param texturesSignature Mojang signature for the textures payload, or {@code null} for unsigned.
 *     Required when the local player is signed in: PlayerInfo.createSkinLookup uses requireSecure
 *     for non-local players, which filters unsigned skins to a default Steve.
 * @param equipmentSnbt Map from slot name ({@code head}, {@code mainhand}, {@code offhand}, {@code
 *     chest}, {@code legs}, {@code feet}) to the captured SNBT for the item in that slot. Empty map
 *     means no equipment.
 */
public record GhostSpec(
    UUID uuid,
    String name,
    String texturesValue,
    String texturesSignature,
    Map<String, String> equipmentSnbt) {

  public static final Map<String, String> NO_EQUIPMENT = Map.of();
}
