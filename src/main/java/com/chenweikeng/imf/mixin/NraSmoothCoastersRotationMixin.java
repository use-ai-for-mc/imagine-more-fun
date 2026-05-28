package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.coaster.CoasterTiltAmplifier;
import org.joml.Quaternionfc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Scales SmoothCoasters' camera lean by the global "Coaster Tilt" multiplier — applies to every
 * coaster SmoothCoasters tilts.
 *
 * <p>SmoothCoasters routes every camera rotation the server sends through {@code
 * SmoothCoasters#setRotation(Quaternionfc target, int ticks)} (the single funnel shared by every
 * protocol version — V4/V5 inherit V6's rotation handler) before interpolating it onto the camera.
 * This intercepts the target pose at the head of that method and replaces it with a roll-scaled
 * copy from {@link CoasterTiltAmplifier}, so SmoothCoasters' own interpolation and camera write
 * carry the adjusted lean. When the multiplier is 1.0 (or off ImagineFun) the pose passes through
 * untouched.
 *
 * <p>{@code @Pseudo} + a string target make this a soft dependency: SmoothCoasters isn't on the
 * compile classpath, and the mixin is silently skipped if the mod is absent at runtime. {@code
 * remap = false} keeps Loom from trying to remap the SmoothCoasters method name. This replaced the
 * baked-track {@code CoasterCameraBank}/{@code NraCameraRollMixin} approach.
 */
@Pseudo
@Mixin(targets = "me.m56738.smoothcoasters.SmoothCoasters", remap = false)
public class NraSmoothCoastersRotationMixin {

  @ModifyVariable(
      method = "setRotation(Lorg/joml/Quaternionfc;I)V",
      at = @At("HEAD"),
      argsOnly = true,
      ordinal = 0,
      remap = false)
  private Quaternionfc imf$amplifyTilt(Quaternionfc target) {
    return CoasterTiltAmplifier.amplify(target);
  }
}
