package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.pim.PimClient;
import com.chenweikeng.imf.pim.hoarder.PinHoarderAutoConfirm;
import com.chenweikeng.imf.pim.hoarder.PinHoarderHelper;
import com.chenweikeng.imf.pim.pinpack.PinPackOverlayRenderer;
import com.chenweikeng.imf.pim.screen.PinBookHandler;
import com.chenweikeng.imf.pim.screen.PinDetailHandler;
import com.chenweikeng.imf.pim.screen.PinRarityHandler;
import com.chenweikeng.imf.pim.tracker.BossBarTracker;
import com.chenweikeng.imf.pim.tracker.ClipboardParser;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class PimClientTickMixin {
  @Inject(method = "tick", at = @At("RETURN"))
  private void imf$tick(CallbackInfo ci) {
    if (!PimClient.isImagineFunServer()) {
      return;
    }

    BossBarTracker.getInstance().update();
    PinRarityHandler.getInstance().tick();
    PinBookHandler.getInstance().tick();
    PinDetailHandler.getInstance().tick();
    ClipboardParser.getInstance().tick();
    PinPackOverlayRenderer.tick();
    PinHoarderHelper.tick();
    PinHoarderAutoConfirm.tick();
  }
}
