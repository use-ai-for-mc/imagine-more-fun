package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.GameState;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.handler.FireworkViewingHandler;
import com.chenweikeng.imf.nra.spacemountain.SpaceMountainOverride;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class NraLivingEntityMixin {
  @Inject(
      method = "hasEffect(Lnet/minecraft/core/Holder;)Z",
      at = @At("RETURN"),
      cancellable = true)
  private void injectBlindnessWhenRiding(
      Holder<MobEffect> effect, CallbackInfoReturnable<Boolean> cir) {
    if (!NotRidingAlertClient.isImagineFunServer()) {
      return;
    }
    LivingEntity entity = (LivingEntity) (Object) this;
    if (entity instanceof net.minecraft.client.player.LocalPlayer) {
      if (FireworkViewingHandler.getInstance().isViewingFirework()) {
        if (effect == MobEffects.BLINDNESS || effect == MobEffects.NIGHT_VISION) {
          cir.setReturnValue(false);
        }
        return;
      }
      boolean isRiding = GameState.getInstance().isRiding();
      if (ModConfig.currentSetting.blindWhenRiding && effect == MobEffects.BLINDNESS && isRiding) {
        cir.setReturnValue(true);
      } else if (effect == MobEffects.NIGHT_VISION) {
        if (shouldHaveFullbright(isRiding)) {
          cir.setReturnValue(true);
        }
      }
    }
  }

  @Inject(
      method =
          "getEffect(Lnet/minecraft/core/Holder;)Lnet/minecraft/world/effect/MobEffectInstance;",
      at = @At("RETURN"),
      cancellable = true)
  private void injectBlindnessInstanceWhenRiding(
      Holder<MobEffect> effect, CallbackInfoReturnable<MobEffectInstance> cir) {
    if (!NotRidingAlertClient.isImagineFunServer()) {
      return;
    }
    LivingEntity entity = (LivingEntity) (Object) this;
    if (entity instanceof net.minecraft.client.player.LocalPlayer) {
      if (FireworkViewingHandler.getInstance().isViewingFirework()) {
        if (effect == MobEffects.BLINDNESS || effect == MobEffects.NIGHT_VISION) {
          cir.setReturnValue(null);
        }
        return;
      }
      boolean isRiding = GameState.getInstance().isRiding();
      if (ModConfig.currentSetting.blindWhenRiding && effect == MobEffects.BLINDNESS && isRiding) {
        if (cir.getReturnValue() == null) {
          cir.setReturnValue(new MobEffectInstance(MobEffects.BLINDNESS, -1));
        }
      } else if (effect == MobEffects.NIGHT_VISION) {
        if (shouldHaveFullbright(isRiding) && cir.getReturnValue() == null) {
          cir.setReturnValue(new MobEffectInstance(MobEffects.NIGHT_VISION, -1));
        }
      }
    }
  }

  /**
   * Whether the mod's fullbright override (forced night vision) should be on right now. It is
   * forced off while the Space Mountain dark-ride override is active — the dome must stay dark for
   * the star effect — regardless of the configured fullbright mode.
   */
  private static boolean shouldHaveFullbright(boolean isRiding) {
    return ModConfig.currentSetting.fullbrightMode.shouldApply(isRiding)
        && !SpaceMountainOverride.isActive();
  }
}
