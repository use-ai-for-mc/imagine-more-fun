package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.GameState;
import com.chenweikeng.imf.nra.ServerState;
import com.chenweikeng.imf.nra.report.ui.RideReportScreen;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class NraMinecraftMixin {
  @Inject(method = "setWindowActive", at = @At("HEAD"), cancellable = true)
  private void imf$onSetWindowActive(boolean bl, CallbackInfo ci) {
    GameState state = GameState.getInstance();
    if (!bl && (state.isAutomaticallyReleasedCursor() || state.isWithinWindowRestoreGrace())) {
      ci.cancel();
    }
  }

  /**
   * On ImagineFun, override the Advancements key to open the Ride Report instead. The vanilla
   * Advancements screen is meaningless on this server, so we intercept before handleKeybinds()
   * processes it.
   */
  @Inject(method = "handleKeybinds", at = @At("HEAD"))
  private void imf$overrideAdvancementsKey(CallbackInfo ci) {
    if (!ServerState.isImagineFunServer()) {
      return;
    }
    Minecraft client = (Minecraft) (Object) this;
    if (client.player == null || client.screen != null) {
      return;
    }
    while (client.options.keyAdvancements.consumeClick()) {
      client.setScreen(RideReportScreen.createLive(null));
    }
  }
}
