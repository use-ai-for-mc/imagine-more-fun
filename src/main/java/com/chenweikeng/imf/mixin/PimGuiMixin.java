package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.pim.PimClient;
import com.chenweikeng.imf.pim.pinpack.PinPackColorAnalyzer;
import com.chenweikeng.imf.pim.screen.PinDetailHandler;
import com.chenweikeng.imf.pim.screen.PinRarityHandler;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class PimGuiMixin {
  @Shadow private Minecraft minecraft;

  @Inject(at = @At("HEAD"), method = "renderSlot", cancellable = true)
  private void imf$onRenderSlot(
      GuiGraphics guiGraphics,
      int i,
      int j,
      DeltaTracker deltaTracker,
      Player player,
      ItemStack itemStack,
      int k,
      CallbackInfo ci) {
    if (!PimClient.isImagineFunServer()) {
      return;
    }

    if (itemStack.isEmpty()) {
      return;
    }

    // Check for pin packs first
    Integer packColor = PinPackColorAnalyzer.getPackColor(itemStack);
    if (packColor != null) {
      imf$renderSlotWithColor(
          guiGraphics, i, j, deltaTracker, player, itemStack, k, packColor, null);
      ci.cancel();
      return;
    }

    // Check for pins
    String pinSeries = PinDetailHandler.getInstance().parsePinSeriesFromLore(itemStack);
    if (pinSeries == null) {
      return;
    }

    PinDetailHandler.PinDetailEntry onBoardEntry =
        PinDetailHandler.getInstance().parsePinEntry(itemStack);
    if (onBoardEntry == null) {
      return;
    }

    PinRarityHandler.PinSeriesEntry pinSeriesEntry =
        PinRarityHandler.getInstance().getSeriesEntry(pinSeries);

    if (onBoardEntry.condition == PinDetailHandler.PinCondition.MINT
        && pinSeriesEntry != null
        && pinSeriesEntry.availability == PinRarityHandler.Availability.REQUIRED) {

      PinDetailHandler.PinDetailEntry pinDetailEntry =
          PinDetailHandler.getInstance().findDetailEntry(pinSeries, onBoardEntry.pinName);

      if (pinDetailEntry != null
          && (pinDetailEntry.condition == PinDetailHandler.PinCondition.LOCKED
              || pinDetailEntry.condition == PinDetailHandler.PinCondition.NOTMINT)) {
        // GREEN: MINT pin needed for collection
        imf$renderSlotWithColor(
            guiGraphics, i, j, deltaTracker, player, itemStack, k, 0xFF00FF00, null);
      } else {
        // YELLOW: MINT pin already collected
        imf$renderSlotWithColor(
            guiGraphics, i, j, deltaTracker, player, itemStack, k, 0xFFFFE000, 0x80FFE000);
      }
      ci.cancel();
    } else if (onBoardEntry.condition != PinDetailHandler.PinCondition.MINT) {
      // MAGENTA: Non-MINT pin
      imf$renderSlotWithColor(
          guiGraphics, i, j, deltaTracker, player, itemStack, k, null, 0x80FF00FF);
      ci.cancel();
    }
  }

  private void imf$renderSlotWithColor(
      GuiGraphics guiGraphics,
      int i,
      int j,
      DeltaTracker deltaTracker,
      Player player,
      ItemStack itemStack,
      int k,
      Integer fillBeforeColor,
      Integer fillAfterColor) {
    if (fillBeforeColor != null) {
      guiGraphics.fill(i, j, i + 16, j + 16, fillBeforeColor);
    }

    float f = itemStack.getPopTime() - deltaTracker.getGameTimeDeltaPartialTick(false);
    if (f > 0.0F) {
      float g = 1.0F + f / 5.0F;
      guiGraphics.pose().pushMatrix();
      guiGraphics.pose().translate(i + 8, j + 12);
      guiGraphics.pose().scale(1.0F / g, (g + 1.0F) / 2.0F);
      guiGraphics.pose().translate(-(i + 8), -(j + 12));
    }

    guiGraphics.renderItem(player, itemStack, i, j, k);

    if (f > 0.0F) {
      guiGraphics.pose().popMatrix();
    }

    if (fillAfterColor != null) {
      guiGraphics.fill(i, j, i + 16, j + 16, fillAfterColor);
    }

    guiGraphics.renderItemDecorations(this.minecraft.font, itemStack, i, j);
  }
}
