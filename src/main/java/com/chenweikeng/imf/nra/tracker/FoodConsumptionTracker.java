package com.chenweikeng.imf.nra.tracker;

import com.chenweikeng.imf.nra.session.SessionTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Tracks food consumption for items with FOOD_TYPE NBT data. Detects when a food item is used and
 * confirms consumption by checking if the stack count decreased.
 */
public final class FoodConsumptionTracker {

  private static final FoodConsumptionTracker INSTANCE = new FoodConsumptionTracker();

  // Pending food use tracking
  private String pendingFoodName = null;
  private Item pendingItemType = null;
  private int pendingSlot = -1;
  private int pendingStackSize = 0;
  private int ticksToCheck = 0;
  private static final int CHECK_TICKS = 40; // Check for ~2 seconds after use starts

  private FoodConsumptionTracker() {}

  public static FoodConsumptionTracker getInstance() {
    return INSTANCE;
  }

  /**
   * Called when the player starts using an item. Checks if it's a food item with FOOD_TYPE NBT and
   * begins tracking for consumption.
   */
  public void onItemUseStart(ItemStack stack, int slot) {
    if (stack.isEmpty()) {
      return;
    }

    // Check for FOOD_TYPE in custom data
    CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
    if (customData == null) {
      return;
    }

    CompoundTag nbt = customData.copyTag();
    if (!nbt.contains("FOOD_TYPE")) {
      return;
    }

    // This is a tracked food item - record it for consumption check
    String itemName = stack.getHoverName().getString();
    pendingFoodName = itemName;
    pendingItemType = stack.getItem();
    pendingSlot = slot;
    pendingStackSize = stack.getCount();
    ticksToCheck = CHECK_TICKS;
  }

  /** Called every client tick to check if pending food was consumed. */
  public void tick() {
    if (pendingFoodName == null || ticksToCheck <= 0) {
      return;
    }

    ticksToCheck--;

    Minecraft mc = Minecraft.getInstance();
    LocalPlayer player = mc.player;
    if (player == null) {
      clearPending();
      return;
    }

    Inventory inventory = player.getInventory();
    ItemStack currentStack = inventory.getItem(pendingSlot);

    // Check current count (0 if empty)
    int currentCount = currentStack.isEmpty() ? 0 : currentStack.getCount();

    // If slot is empty or has fewer items, check if it was consumed vs moved
    if (currentCount < pendingStackSize) {
      // Slot has fewer items - could be consumption or move
      // If slot is empty or has same item type, it's consumption
      // If slot has different item type, it was swapped
      if (!currentStack.isEmpty() && currentStack.getItem() != pendingItemType) {
        // Different item in slot - was swapped, not consumed
        clearPending();
        return;
      }

      // Food was consumed! (slot empty or same item with lower count)
      String consumedName = pendingFoodName;
      // Record the consumption
      SessionTracker.getInstance().onFoodConsumed(consumedName);

      clearPending();
      return;
    }

    // Verify the item is still the same type and name (not moved/swapped)
    if (currentStack.getItem() != pendingItemType
        || !currentStack.getHoverName().getString().equals(pendingFoodName)) {
      // Item was swapped with same count - cancel tracking
      clearPending();
      return;
    }

    // If we've run out of ticks to check, cancel tracking
    if (ticksToCheck <= 0) {
      clearPending();
    }
  }

  private void clearPending() {
    pendingFoodName = null;
    pendingItemType = null;
    pendingSlot = -1;
    pendingStackSize = 0;
    ticksToCheck = 0;
  }

  /**
   * Checks if an ItemStack is a trackable food item (has FOOD_TYPE NBT).
   *
   * @param stack The ItemStack to check
   * @return true if the item has FOOD_TYPE NBT data
   */
  public static boolean isTrackableFood(ItemStack stack) {
    if (stack.isEmpty()) {
      return false;
    }

    CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
    if (customData == null) {
      return false;
    }

    CompoundTag nbt = customData.copyTag();
    return nbt.contains("FOOD_TYPE");
  }
}
