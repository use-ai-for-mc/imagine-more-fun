package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.pim.PimClient;
import com.chenweikeng.imf.pim.tracker.BossBarTracker;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BossHealthOverlay.class)
public class PimBossHealthOverlayMixin {
  @Unique private LerpingBossEvent pimBossEvent;

  @Shadow private Map<UUID, LerpingBossEvent> events;

  @Inject(method = "<init>", at = @At("RETURN"))
  private void imf$init(CallbackInfo ci) {
    pimBossEvent =
        new LerpingBossEvent(
            BossBarTracker.PIN_TRADER_BOSS_ID,
            Component.empty(),
            1.0f,
            BossEvent.BossBarColor.PINK,
            BossEvent.BossBarOverlay.PROGRESS,
            false,
            false,
            false);
  }

  @Inject(method = "extractRenderState", at = @At("HEAD"))
  private void imf$render(GuiGraphicsExtractor guiGraphics, CallbackInfo ci) {
    if (!PimClient.isImagineFunServer()) {
      return;
    }

    if (BossBarTracker.getInstance().isEnabled()) {
      Component displayTitle = BossBarTracker.getInstance().getDisplayTitle();
      if (!displayTitle.getString().isEmpty()) {
        pimBossEvent.setName(displayTitle);
        pimBossEvent.setProgress(1.0f);
        events.put(BossBarTracker.PIN_TRADER_BOSS_ID, pimBossEvent);
      } else {
        events.remove(BossBarTracker.PIN_TRADER_BOSS_ID);
      }
    } else {
      events.remove(BossBarTracker.PIN_TRADER_BOSS_ID);
    }
  }
}
