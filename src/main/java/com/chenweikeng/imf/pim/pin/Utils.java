package com.chenweikeng.imf.pim.pin;

import com.chenweikeng.imf.pim.screen.PinDetailHandler;
import com.chenweikeng.imf.pim.screen.PinRarityHandler;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

public class Utils {
  public static Set<String> getPlayerInventoryMintPins() {
    Set<String> mintPins = new HashSet<>();
    Minecraft mc = Minecraft.getInstance();

    if (mc.player == null) {
      return mintPins;
    }

    var inv = mc.player.getInventory();
    var items = inv.getNonEquipmentItems();

    for (ItemStack stack : items) {
      processItemStack(stack, mintPins);
    }

    return mintPins;
  }

  private static void processItemStack(ItemStack stack, Set<String> mintPins) {
    if (stack.isEmpty()) {
      return;
    }

    if (isShulkerBox(stack)) {
      if (stack.has(DataComponents.CONTAINER)) {
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);

        for (ItemStack inside : container.allItemsCopyStream().toList()) {
          processItemStack(inside, mintPins);
        }
      }
      return;
    }

    PinDetailHandler.PinDetailEntry entry = PinDetailHandler.getInstance().parsePinEntry(stack);

    if (entry != null && entry.condition == PinDetailHandler.PinCondition.MINT) {
      String pinSeries = PinDetailHandler.getInstance().parsePinSeriesFromLore(stack);
      String pinName = entry.pinName;

      if (pinSeries != null && pinName != null) {
        PinRarityHandler.PinSeriesEntry seriesEntry =
            PinRarityHandler.getInstance().getSeriesEntry(pinSeries);

        if (seriesEntry != null
            && seriesEntry.availability == PinRarityHandler.Availability.REQUIRED) {
          mintPins.add(pinSeries + ":" + pinName);
        }
      }
    }
  }

  private static boolean isShulkerBox(ItemStack stack) {
    return stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
  }
}
