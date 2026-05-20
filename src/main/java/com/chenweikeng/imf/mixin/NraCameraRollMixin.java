package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.coaster.CoasterCameraBank;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rolls the camera through banked coaster turns — any ride that has a baked track with per-sample
 * roll, gated per-ride via {@link CoasterCameraBank}.
 *
 * <p>Injects at the HEAD of {@link GameRenderer#renderLevel} and post-multiplies a roll about the
 * view-forward axis into {@code camera.rotation()}, using the angle from {@link CoasterCameraBank}.
 * {@code renderLevel} builds the world modelview straight from {@code camera.rotation()} (and the
 * frustum from it), so this banks the whole view while the separate 2D HUD pass stays level.
 *
 * <p><b>Why {@code renderLevel} HEAD and not {@code Camera.setRotation}:</b> SmoothCoasters injects
 * at the RETURN of {@code GameRenderer.updateCamera} and there does {@code camera.rotation().set(
 * ...)} — it overwrites the camera rotation outright. {@code render()} calls {@code updateCamera()}
 * and only then {@code renderLevel()}, so a roll applied inside {@code updateCamera} (e.g. at
 * {@code Camera.setRotation}) would be clobbered by SmoothCoasters. Applying it at {@code
 * renderLevel} HEAD lands strictly after SmoothCoasters' rewrite — and still before the modelview
 * and frustum are built — so the bank survives whether or not SmoothCoasters is engaged.
 */
@Mixin(GameRenderer.class)
public abstract class NraCameraRollMixin {
  @Shadow
  public abstract Camera getMainCamera();

  @Inject(method = "renderLevel", at = @At("HEAD"))
  private void imf$applyBankRoll(DeltaTracker deltaTracker, CallbackInfo ci) {
    float rollDeg = CoasterCameraBank.getCurrentRoll();
    if (Math.abs(rollDeg) > 0.01f) {
      Camera camera = getMainCamera();
      if (camera != null) {
        camera.rotation().rotateZ(rollDeg * (float) (Math.PI / 180.0));
      }
    }
  }
}
