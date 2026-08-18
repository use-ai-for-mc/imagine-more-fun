package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.canoe.CanoeHelperClient;
import com.chenweikeng.imf.nra.showtime.ShowtimeCountdownController;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forwards every action-bar update (overlay message) to IMF features that consume it. */
@Mixin(Gui.class)
public class CanoeGuiSetOverlayMessageMixin {

  @Inject(at = @At("HEAD"), method = "setOverlayMessage")
  private void imf$onSetOverlayMessage(Component message, boolean animate, CallbackInfo ci) {
    CanoeHelperClient.get().onActionBar(message);
    ShowtimeCountdownController.getInstance().onActionBar(message);
  }
}
