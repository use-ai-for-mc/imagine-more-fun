package com.chenweikeng.imf.pim.hoarder;

import com.chenweikeng.imf.pim.PimClient;
import com.chenweikeng.imf.pim.screen.PinDetailHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Helper for Pin Hoarder trading automation. After a trade completes and the current hotbar slot
 * becomes empty, automatically advances to the next slot containing a non-MINT pin. If no non-MINT
 * pins remain in hotbar but useful pin packs exist in inventory, opens the inventory screen.
 */
public final class PinHoarderHelper {

  private static boolean pendingSlotCheck = false;
  private static int ticksUntilCheck = 0;

  private PinHoarderHelper() {}

  /** Called when a Pin Hoarder trade completes (coin pickup message detected). */
  public static void onTradeCompleted() {
    // Schedule a check for a few ticks later to ensure inventory is updated
    pendingSlotCheck = true;
    ticksUntilCheck = 2;
    PimClient.LOGGER.debug("[PinHoarder] Trade completed, scheduling slot check");
  }

  /** Called every client tick. */
  public static void tick() {
    if (!pendingSlotCheck) {
      return;
    }

    if (ticksUntilCheck > 0) {
      ticksUntilCheck--;
      return;
    }

    pendingSlotCheck = false;
    tryAdvanceToNextPin();
  }

  private static void tryAdvanceToNextPin() {
    Minecraft mc = Minecraft.getInstance();
    Player player = mc.player;
    if (player == null) {
      PimClient.LOGGER.debug("[PinHoarder] No player, aborting");
      return;
    }

    Inventory inventory = player.getInventory();
    int currentSlot = inventory.getSelectedSlot();
    PimClient.LOGGER.debug("[PinHoarder] Checking slot {}", currentSlot);

    // Check if current slot is empty
    ItemStack currentItem = inventory.getItem(currentSlot);
    if (!currentItem.isEmpty()) {
      // Current slot still has an item - don't advance
      PimClient.LOGGER.debug(
          "[PinHoarder] Current slot not empty: {}", currentItem.getHoverName().getString());
      return;
    }

    PimClient.LOGGER.debug("[PinHoarder] Current slot empty, searching for next pin");

    // Find next slot with a non-MINT pin in hotbar (slots 0-8)
    for (int i = 1; i < 9; i++) {
      int checkSlot = (currentSlot + i) % 9;
      ItemStack stack = inventory.getItem(checkSlot);

      if (stack.isEmpty()) {
        continue;
      }

      PinDetailHandler.PinDetailEntry entry = PinDetailHandler.parsePinEntry(stack);
      if (entry != null && entry.condition != PinDetailHandler.PinCondition.MINT) {
        // Found a non-MINT pin - switch to this slot
        PimClient.LOGGER.debug("[PinHoarder] Found non-MINT pin in slot {}, switching", checkSlot);
        inventory.setSelectedSlot(checkSlot);
        return;
      }
    }

    PimClient.LOGGER.debug("[PinHoarder] No pins in hotbar, checking for packs");

    // No non-MINT pins found in hotbar - check if hotbar has useful pin packs first
    if (hasUsefulPinPackInHotbar(inventory)) {
      // There's a useful pack in hotbar - player can switch to it manually
      PimClient.LOGGER.debug("[PinHoarder] Found useful pack in hotbar, not opening inventory");
      return;
    }

    // Check if main inventory (non-hotbar) has useful pin packs
    if (hasUsefulPinPacksInMainInventory(inventory)) {
      // Open the inventory screen
      PimClient.LOGGER.debug("[PinHoarder] Found useful packs in inventory, opening inventory");
      mc.setScreenAndShow(new InventoryScreen(player));
    } else {
      PimClient.LOGGER.debug("[PinHoarder] No useful packs found");
    }
  }

  /**
   * Checks if the hotbar (slots 0-8) contains an unopened pin pack or a pack with non-MINT pins.
   */
  private static boolean hasUsefulPinPackInHotbar(Inventory inventory) {
    for (int slot = 0; slot < 9; slot++) {
      ItemStack stack = inventory.getItem(slot);
      if (stack.isEmpty()) {
        continue;
      }

      if (isUsefulPinPack(stack)) {
        PimClient.LOGGER.debug("[PinHoarder] Found useful pack in hotbar slot {}", slot);
        return true;
      }
    }
    return false;
  }

  /**
   * Checks if the main inventory (slots 9-35, excluding hotbar) contains pin packs with non-MINT
   * pins that can be traded to Pin Hoarder.
   */
  private static boolean hasUsefulPinPacksInMainInventory(Inventory inventory) {
    for (int slot = 9; slot < 36; slot++) {
      ItemStack stack = inventory.getItem(slot);
      if (stack.isEmpty()) {
        continue;
      }

      if (isUsefulPinPack(stack)) {
        PimClient.LOGGER.debug("[PinHoarder] Found useful pack in inventory slot {}", slot);
        return true;
      }
    }
    return false;
  }

  /**
   * Checks if a pin pack is useful for Pin Hoarder trading. A pack is useful if it's either
   * unopened OR contains non-MINT pins.
   */
  private static boolean isUsefulPinPack(ItemStack stack) {
    String name = stack.getHoverName().getString();
    if (!name.contains("Pin Pack")) {
      return false;
    }

    // Check if pack is opened
    boolean isOpened = name.contains("(Opened)");
    var customData = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
    if (customData != null) {
      String nbtStr = customData.copyTag().toString();
      isOpened = isOpened || nbtStr.contains("IS_PACK_OPENED:1b");
    }

    // Unopened packs are useful (can be opened for pins)
    if (!isOpened) {
      return true;
    }

    // For opened packs, check if they have non-mint pins
    // NO_STAR packs (no ⭐) definitely have non-mint pins
    if (!name.contains("⭐")) {
      return true;
    }

    // Star packs that are opened - check the actual contents
    if (customData != null) {
      String nbtStr = customData.copyTag().toString();
      // If any pin inside is NOT BRAND_NEW_MINT_CONDITION, it's non-mint
      if (nbtStr.contains("SLIGHTLY_SCRATCHED")
          || nbtStr.contains("WELL_KEPT")
          || nbtStr.contains("HEAVILY_WORN")
          || nbtStr.contains("MODERATE_WEAR")
          || nbtStr.contains("HEAVY_DAMAGE")
          || nbtStr.contains("FADED")
          || nbtStr.contains("DAMAGED")) {
        return true;
      }
    }

    return false;
  }
}
