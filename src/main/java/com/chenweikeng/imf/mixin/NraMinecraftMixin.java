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
  /**
   * Keep the game from opening its pause screen when IMF deliberately releases the cursor during a
   * ride. Window focus itself must still be recorded by vanilla: Minecraft 26.2's TextInputManager
   * relies on Window.isFocused() to stop changing the system IME after the user switches to another
   * application.
   */
  @Inject(
      method = "pauseIfInactive",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;pauseGame(Z)V"),
      cancellable = true)
  private void imf$suppressPauseAfterAutomaticCursorRelease(CallbackInfo ci) {
    GameState state = GameState.getInstance();
    if (state.isAutomaticallyReleasedCursor() || state.isWithinWindowRestoreGrace()) {
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
    if (client.player == null || client.gui.screen() != null) {
      return;
    }
    while (client.options.keyAdvancements.consumeClick()) {
      client.setScreenAndShow(RideReportScreen.createLive(null));
    }
  }
}
