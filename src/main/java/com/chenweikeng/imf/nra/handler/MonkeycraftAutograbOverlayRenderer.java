package com.chenweikeng.imf.nra.handler;

import com.chenweikeng.imf.nra.GameState;
import com.chenweikeng.imf.nra.ServerState;
import com.chenweikeng.imf.nra.compat.MonkeycraftCompat;
import com.chenweikeng.imf.nra.ride.AutograbHolder;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.Blocks;

/** Full-screen MonkeyCraft-visible cue while waiting in an autograb region. */
public final class MonkeycraftAutograbOverlayRenderer {
  private static final int MIN_TEXTURE_ALPHA = 128;
  private static final int MAX_TEXTURE_ALPHA = 184;
  private static final int PURPLE_TINT = 0x708A00FF;
  private static final double PULSE_PERIOD_TICKS = 40.0;

  private MonkeycraftAutograbOverlayRenderer() {}

  public static void render(GuiGraphics context, DeltaTracker tickCounter) {
    Minecraft client = Minecraft.getInstance();
    if (!shouldRender(client)) {
      return;
    }

    float partialTick = tickCounter.getGameTimeDeltaPartialTick(false);
    double phase =
        (GameState.getInstance().getAbsoluteTickCounter() + partialTick)
            * Math.PI
            * 2.0
            / PULSE_PERIOD_TICKS;
    int alpha =
        MIN_TEXTURE_ALPHA
            + (int)
                Math.round((Math.sin(phase) + 1.0) * 0.5 * (MAX_TEXTURE_ALPHA - MIN_TEXTURE_ALPHA));
    int textureColor = (alpha << 24) | 0xFFFFFF;

    TextureAtlasSprite portalSprite =
        client
            .getBlockRenderer()
            .getBlockModelShaper()
            .getParticleIcon(Blocks.NETHER_PORTAL.defaultBlockState());
    context.blitSprite(
        RenderPipelines.GUI_TEXTURED,
        portalSprite,
        0,
        0,
        context.guiWidth(),
        context.guiHeight(),
        textureColor);
    context.fill(0, 0, context.guiWidth(), context.guiHeight(), PURPLE_TINT);
  }

  private static boolean shouldRender(Minecraft client) {
    if (!ServerState.isImagineFunServer()
        || !MonkeycraftCompat.isClientConnected()
        || client.player == null) {
      return false;
    }

    return !GameState.getInstance().isValidPassenger(client.player)
        && AutograbHolder.getRideAtLocation(client) != null;
  }
}
