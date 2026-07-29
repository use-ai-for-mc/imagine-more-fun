package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.quest.QuestCollectibleGlow;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Presents quest collectibles at night without changing the server-synchronized world time. */
@Mixin(Level.class)
public class ImfQuestNightMixin {
  private static final long MIDNIGHT = 18000L;

  @Inject(method = "getDayTime", at = @At("HEAD"), cancellable = true)
  private void imf$forceNightForQuestCollectible(CallbackInfoReturnable<Long> cir) {
    if (QuestCollectibleGlow.shouldForceNight()) {
      cir.setReturnValue(MIDNIGHT);
    }
  }
}
