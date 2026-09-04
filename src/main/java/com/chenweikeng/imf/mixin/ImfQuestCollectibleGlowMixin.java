package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.quest.QuestCollectibleGlow;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies a client-only outline without changing the server-synchronized entity flags. */
@Mixin(Minecraft.class)
public class ImfQuestCollectibleGlowMixin {
  @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
  private void imf$showQuestCollectibleOutline(Entity entity, CallbackInfoReturnable<Boolean> cir) {
    if (QuestCollectibleGlow.isMarked(entity)) {
      cir.setReturnValue(true);
    }
  }
}
