package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.report.ui.RideReportScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects at the end of {@link GameRenderer#render} to support screenshot capture with the full GUI
 * composited. At this point {@code guiRenderer.render()} has committed all GUI draws to the render
 * target, so the framebuffer contains the complete frame (world + screen overlay + models).
 */
@Mixin(GameRenderer.class)
public class NraGameRendererMixin {
  @Inject(method = "render", at = @At("TAIL"))
  private void imf$onPostRender(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
    RideReportScreen.executePendingCapture();
  }
}
