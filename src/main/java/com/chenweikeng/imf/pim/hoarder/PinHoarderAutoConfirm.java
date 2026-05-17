package com.chenweikeng.imf.pim.hoarder;

import com.chenweikeng.imf.pim.PimClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/**
 * Handles automatic confirmation of Pin Hoarder trades. When the "Confirm Trade" screen opens and
 * the player is near a known Pin Hoarder location, automatically clicks the Confirm button.
 */
public final class PinHoarderAutoConfirm {

  private static final String CONFIRM_TRADE_TITLE = "Confirm Trade";
  private static final int CONFIRM_SLOT = 15;
  private static final double MAX_DISTANCE = 8.0;

  // Known Pin Hoarder locations (dimension, x, y, z)
  private static final PinHoarderLocation[] KNOWN_LOCATIONS = {
    new PinHoarderLocation("minecraft:dlnew", -156.5, 62, 649.5),
  };

  private static boolean pendingAutoConfirm = false;
  private static int ticksUntilConfirm = 0;

  private PinHoarderAutoConfirm() {}

  /**
   * Called when container content is received. Checks if this is a Pin Hoarder confirm screen and
   * schedules auto-confirm if conditions are met.
   */
  public static void onContainerContentReceived() {
    Minecraft mc = Minecraft.getInstance();
    Screen screen = mc.screen;

    if (!(screen instanceof AbstractContainerScreen<?>)) {
      return;
    }

    String title = screen.getTitle().getString();
    if (!CONFIRM_TRADE_TITLE.equals(title)) {
      return;
    }

    if (!isNearPinHoarder()) {
      PimClient.LOGGER.info(
          "[PinHoarderAutoConfirm] Confirm Trade screen but not near Pin Hoarder");
      return;
    }

    // Schedule auto-confirm for next tick to ensure screen is fully initialized
    pendingAutoConfirm = true;
    ticksUntilConfirm = 1;
    PimClient.LOGGER.info("[PinHoarderAutoConfirm] Scheduling auto-confirm");
  }

  /** Called every client tick. */
  public static void tick() {
    if (!pendingAutoConfirm) {
      return;
    }

    if (ticksUntilConfirm > 0) {
      ticksUntilConfirm--;
      return;
    }

    pendingAutoConfirm = false;
    performAutoConfirm();
  }

  private static void performAutoConfirm() {
    Minecraft mc = Minecraft.getInstance();
    Screen screen = mc.screen;

    if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
      PimClient.LOGGER.info("[PinHoarderAutoConfirm] Screen changed, aborting");
      return;
    }

    String title = screen.getTitle().getString();
    if (!CONFIRM_TRADE_TITLE.equals(title)) {
      PimClient.LOGGER.info("[PinHoarderAutoConfirm] Title changed, aborting");
      return;
    }

    AbstractContainerMenu menu = containerScreen.getMenu();

    // Verify Confirm button is in expected slot
    if (menu.slots.size() <= CONFIRM_SLOT) {
      PimClient.LOGGER.info("[PinHoarderAutoConfirm] Not enough slots, aborting");
      return;
    }

    ItemStack confirmItem = menu.getSlot(CONFIRM_SLOT).getItem();
    if (confirmItem.isEmpty() || !confirmItem.getHoverName().getString().equals("Confirm")) {
      PimClient.LOGGER.info(
          "[PinHoarderAutoConfirm] Confirm button not found in slot {}, found: {}",
          CONFIRM_SLOT,
          confirmItem.getHoverName().getString());
      return;
    }

    // Click the Confirm button
    LocalPlayer player = mc.player;
    if (player == null) {
      return;
    }

    PimClient.LOGGER.info("[PinHoarderAutoConfirm] Clicking Confirm button");

    // Simulate left click on the Confirm slot
    mc.gameMode.handleInventoryMouseClick(
        menu.containerId, CONFIRM_SLOT, 0, ClickType.PICKUP, player);

    // Close the screen immediately
    mc.setScreen(null);
  }

  private static boolean isNearPinHoarder() {
    Minecraft mc = Minecraft.getInstance();
    LocalPlayer player = mc.player;
    if (player == null || mc.level == null) {
      return false;
    }

    String dimension = mc.level.dimension().toString();
    double playerX = player.getX();
    double playerY = player.getY();
    double playerZ = player.getZ();

    for (PinHoarderLocation loc : KNOWN_LOCATIONS) {
      if (!dimension.contains(loc.dimension)) {
        continue;
      }

      double dx = playerX - loc.x;
      double dy = playerY - loc.y;
      double dz = playerZ - loc.z;
      double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

      if (distance <= MAX_DISTANCE) {
        PimClient.LOGGER.info(
            "[PinHoarderAutoConfirm] Player is {} blocks from Pin Hoarder",
            String.format("%.1f", distance));
        return true;
      }
    }

    return false;
  }

  private record PinHoarderLocation(String dimension, double x, double y, double z) {}
}
