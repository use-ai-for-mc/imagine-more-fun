package com.chenweikeng.imf.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the player-list footer, which on ImagineFun carries the current instance name (e.g.
 * "disneyland1") on its first line. Used to detect when the player moves to a different instance
 * sync-group so train predictions can re-anchor.
 */
@Mixin(PlayerTabOverlay.class)
public interface NraPlayerTabOverlayAccessor {
  @Accessor("footer")
  Component getFooter();
}
