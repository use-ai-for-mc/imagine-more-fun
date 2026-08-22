package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.GameState;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public class NraWindowMixin {
  @Inject(method = "onFocus", at = @At("HEAD"), cancellable = true)
  private void imf$onFocus(long handle, boolean focused, CallbackInfo ci) {
    GameState state = GameState.getInstance();
    if (!focused && (state.isAutomaticallyReleasedCursor() || state.isWithinWindowRestoreGrace())) {
      ci.cancel();
    }
  }
}
