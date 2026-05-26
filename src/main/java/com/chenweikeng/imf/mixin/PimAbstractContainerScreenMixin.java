package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.pim.PimClient;
import com.chenweikeng.imf.pim.pinpack.PinPackColorAnalyzer;
import com.chenweikeng.imf.pim.screen.InventoryHandler;
import com.chenweikeng.imf.pim.screen.PinBookHandler;
import com.chenweikeng.imf.pim.screen.PinDetailHandler;
import com.chenweikeng.imf.pim.screen.PinRarityHandler;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public class PimAbstractContainerScreenMixin {
  @Shadow protected int imageWidth;

  @Inject(at = @At("HEAD"), method = "renderSlot", cancellable = true)
  public void imf$onRenderSlot(GuiGraphics guiGraphics, Slot slot, int i, int j, CallbackInfo ci) {
    if (!PimClient.isImagineFunServer()) {
      return;
    }

    AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
    PimScreenAccessor screenAccessor = (PimScreenAccessor) screen;
    int k = slot.x;
    int l = slot.y;

    ItemStack itemStack = slot.getItem();
    if (itemStack.isEmpty()) {
      return;
    }

    Font font = screenAccessor.pim$getFont();
    if (font == null) {
      return;
    }

    if (!(screen instanceof ContainerScreen)
        && !(screen instanceof InventoryScreen)
        && !(screen instanceof ShulkerBoxScreen)) {
      return;
    }

    // Check for pin packs (in player inventory slots, or inside shulker boxes)
    boolean isPlayerInventory = slot.container instanceof Inventory;
    boolean isShulkerBoxContent = screen instanceof ShulkerBoxScreen && !isPlayerInventory;
    if (isPlayerInventory || isShulkerBoxContent) {
      Integer packColor = PinPackColorAnalyzer.getPackColor(itemStack);
      if (packColor != null) {
        imf$renderItemWithFills(guiGraphics, slot, itemStack, font, packColor, null, ci);
        return;
      }
    }

    // Check for pin packs in the Pin Box Trader shop
    String titleStr = screenAccessor.pim$getTitle().getString();
    if (titleStr.contains("Pin Box Trader") && !isPlayerInventory) {
      Integer shopPackColor = PinPackColorAnalyzer.getShopPackColor(itemStack);
      if (shopPackColor != null) {
        imf$renderItemWithFills(guiGraphics, slot, itemStack, font, shopPackColor, null, ci);
        return;
      }
    }

    // Pins in player inventory or shulker box contents
    PinDetailHandler.PinDetailEntry entry2 = PinDetailHandler.parsePinEntry(itemStack);
    if (entry2 != null && (isPlayerInventory || isShulkerBoxContent)) {
      String pinSeries = PinDetailHandler.parsePinSeriesFromLore(itemStack);
      if (pinSeries == null) {
        return;
      }

      if (entry2.condition == PinDetailHandler.PinCondition.MINT) {
        Set<String> missingMintPinNames =
            screen.getMenu() != null
                ? InventoryHandler.getInstance().getMissingMintPinNames(screen.getMenu().slots)
                : Set.of();

        String key = pinSeries + ":" + entry2.pinName;
        if (missingMintPinNames.contains(key)) {
          imf$renderItemWithFills(guiGraphics, slot, itemStack, font, 0xFF00FF00, null, ci);
          return;
        } else {
          imf$renderItemWithFills(guiGraphics, slot, itemStack, font, 0xFFFFE000, 0x80FFE000, ci);
          return;
        }
      } else {
        // Non-MINT pins get MAGENTA
        imf$renderItemWithFills(guiGraphics, slot, itemStack, font, null, 0x80FF00FF, ci);
        return;
      }
    }

    if (!(screen instanceof ContainerScreen)) {
      return;
    }

    if (titleStr.contains("\u4e51")) {
      PinBookHandler.PinBookEntry entry = PinBookHandler.getInstance().parsePinbookEntry(itemStack);

      if (entry != null && slot.getContainerSlot() < 45 && !(slot.container instanceof Inventory)) {
        String itemName = itemStack.getHoverName().getString();
        if (!itemName.startsWith("Pin Pack - ")) {
          return;
        }

        String seriesName = itemName.substring("Pin Pack - ".length());

        Map<String, PinDetailHandler.PinDetailEntry> detailMap =
            PinDetailHandler.getInstance().getSeriesDetails(seriesName);
        boolean shouldBlink = detailMap == null || detailMap.size() != entry.totalMints;

        PinRarityHandler.PinSeriesEntry seriesEntry =
            PinRarityHandler.getInstance().getSeriesEntry(seriesName);
        if (seriesEntry != null
            && seriesEntry.availability == PinRarityHandler.Availability.REQUIRED
            && entry.mintsCollected < entry.totalMints) {
          Set<String> incompleteSeries =
              screen.getMenu() != null
                  ? InventoryHandler.getInstance().getIncompleteSeries(screen.getMenu().slots)
                  : Set.of();
          int fillColor = incompleteSeries.contains(seriesName) ? 0xFF00FF00 : 0xFFFFE000;
          guiGraphics.fill(k, l, k + 16, l + 16, fillColor);

          if (!shouldBlink || System.nanoTime() % 1_000_000_000 < 500_000_000) {
            int m = slot.x + slot.y * this.imageWidth;
            if (slot.isFake()) {
              guiGraphics.renderFakeItem(itemStack, k, l, m);
            } else {
              guiGraphics.renderItem(itemStack, k, l, m);
            }

            guiGraphics.renderItemDecorations(font, itemStack, k, l, null);
          }
          ci.cancel();
        }
      } else {
        if (PinDetailHandler.getCurrentOpenedPinSeries() != null
            && entry2 != null
            && entry2.condition != PinDetailHandler.PinCondition.MINT
            && !(slot.container instanceof Inventory)) {
          Set<String> missingMintPinNames =
              screen.getMenu() != null
                  ? InventoryHandler.getInstance().getMissingMintPinNames(screen.getMenu().slots)
                  : Set.of();

          String key = PinDetailHandler.getCurrentOpenedPinSeries() + ":" + entry2.pinName;
          if (missingMintPinNames.contains(key)) {
            imf$renderItemWithFills(guiGraphics, slot, itemStack, font, 0xFF00FF00, null, ci);
          }
        }
      }
    } else {
      if (slot.container instanceof Inventory) {
        return;
      }

      String pinSeries = PinDetailHandler.getInstance().parsePinSeriesFromLore(itemStack);
      if (pinSeries == null) {
        return;
      }

      String itemName = itemStack.getHoverName().getString();

      PinDetailHandler.PinDetailEntry onBoardEntry =
          PinDetailHandler.getInstance().parsePinEntry(itemStack);
      PinRarityHandler.PinSeriesEntry pinSeriesEntry =
          PinRarityHandler.getInstance().getSeriesEntry(pinSeries);
      PinDetailHandler.PinDetailEntry pinDetailEntry =
          PinDetailHandler.getInstance().findDetailEntry(pinSeries, itemName);

      boolean isPinBoard = titleStr.contains("\u4e54") && titleStr.contains("\u9dfc");

      if (onBoardEntry != null
          && onBoardEntry.condition == PinDetailHandler.PinCondition.MINT
          && pinSeriesEntry != null
          && pinSeriesEntry.availability == PinRarityHandler.Availability.REQUIRED
          && pinDetailEntry != null
          && (pinDetailEntry.condition == PinDetailHandler.PinCondition.LOCKED
              || pinDetailEntry.condition == PinDetailHandler.PinCondition.NOTMINT)) {
        imf$renderItemWithFills(guiGraphics, slot, itemStack, font, 0xFF00FF00, null, ci);
      } else if (onBoardEntry != null
          && onBoardEntry.condition == PinDetailHandler.PinCondition.MINT
          && !isPinBoard) {
        imf$renderItemWithFills(guiGraphics, slot, itemStack, font, 0xFFFFE000, 0x80FFE000, ci);
      } else {
        imf$renderItemWithFills(guiGraphics, slot, itemStack, font, null, 0x80FF00FF, ci);
      }
    }
  }

  @Inject(at = @At("HEAD"), method = "slotClicked")
  public void imf$onSlotClicked(Slot slot, int i, int j, ClickType clickType, CallbackInfo ci) {
    if (!PimClient.isImagineFunServer()) {
      return;
    }

    if (slot == null) {
      return;
    }

    if (slot.getContainerSlot() >= 45 || slot.container instanceof Inventory) {
      return;
    }

    AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
    PimScreenAccessor screenAccessor = (PimScreenAccessor) screen;

    if (!(screen instanceof ContainerScreen)) {
      return;
    }

    String titleStr = screenAccessor.pim$getTitle().getString();
    if (!titleStr.contains("\u4e51")) {
      return;
    }

    ItemStack itemStack = slot.getItem();

    if (itemStack.isEmpty()) {
      return;
    }

    String itemName = itemStack.getHoverName().getString();
    if (itemName.startsWith("Pin Pack - ")) {
      String seriesName = itemName.substring("Pin Pack - ".length());
      PinRarityHandler.PinSeriesEntry seriesEntry =
          PinRarityHandler.getInstance().getSeriesEntry(seriesName);
      if (seriesEntry != null
          && seriesEntry.availability == PinRarityHandler.Availability.OPTIONAL) {
        PinDetailHandler.setCurrentOpenedPinSeries(null);
      } else {
        PinDetailHandler.setCurrentOpenedPinSeries(seriesName);
      }
    }
  }

  private void imf$renderItemWithFills(
      GuiGraphics guiGraphics,
      Slot slot,
      ItemStack itemStack,
      Font font,
      Integer fillBeforeColor,
      Integer fillAfterColor,
      CallbackInfo ci) {
    int k = slot.x;
    int l = slot.y;

    if (fillBeforeColor != null) {
      guiGraphics.fill(k, l, k + 16, l + 16, fillBeforeColor);
    }

    int m = slot.x + slot.y * this.imageWidth;
    if (slot.isFake()) {
      guiGraphics.renderFakeItem(itemStack, k, l, m);
    } else {
      guiGraphics.renderItem(itemStack, k, l, m);
    }

    if (fillAfterColor != null) {
      guiGraphics.fill(k, l, k + 16, l + 16, fillAfterColor);
    }

    guiGraphics.renderItemDecorations(font, itemStack, k, l, null);
    ci.cancel();
  }
}
