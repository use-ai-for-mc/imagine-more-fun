package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.spacemountain.SpaceMountainOverride;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses the dense {@code minecraft:end_rod} particles the server spawns around Space and
 * Hyperspace Mountain riders to fake a starfield. Cancelling at HEAD prevents both {@code
 * makeParticle} and {@code add(Particle)} from running, so there is no allocation, no light
 * tracking, and no per-tick update cost for skipped particles.
 */
@Mixin(ParticleEngine.class)
public class NraParticleEngineMixin {
  @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
  private void imf$skipEndRodOnSpaceMountain(
      ParticleOptions options,
      double x,
      double y,
      double z,
      double xa,
      double ya,
      double za,
      CallbackInfoReturnable<Particle> cir) {
    if (!SpaceMountainOverride.isActive()) return;
    if (options.getType() == ParticleTypes.END_ROD) {
      cir.setReturnValue(null);
    }
  }
}
