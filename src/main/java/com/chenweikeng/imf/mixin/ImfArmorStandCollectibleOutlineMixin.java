package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.quest.QuestCollectibleGlow;
import net.minecraft.client.renderer.entity.ArmorStandRenderer;
import net.minecraft.client.renderer.entity.state.ArmorStandRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Suppresses only the invisible wooden stand's outline while preserving its head-layer outline. */
@Mixin(ArmorStandRenderer.class)
public class ImfArmorStandCollectibleOutlineMixin {
  @Inject(method = "getRenderType", at = @At("HEAD"), cancellable = true)
  private void imf$hideCollectibleStandOutline(
      ArmorStandRenderState state,
      boolean isBodyVisible,
      boolean forceTransparent,
      boolean appearGlowing,
      CallbackInfoReturnable<@Nullable RenderType> cir) {
    if (!isBodyVisible
        && appearGlowing
        && QuestCollectibleGlow.isMarkedPosition(state.x, state.y, state.z)) {
      cir.setReturnValue(null);
    }
  }
}
