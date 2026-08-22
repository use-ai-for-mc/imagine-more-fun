package com.chenweikeng.imf.pim.pinpack;

import com.chenweikeng.imf.nra.session.SessionTracker;
import com.chenweikeng.imf.pim.PimClient;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Renders a pin pack statistics overlay on the right side of the player inventory screen. Shows
 * counts of opened/unopened packs and provides a clickable "Open All" button that auto-opens packs
 * one at a time.
 */
public final class PinPackOverlayRenderer {

  private static final int PADDING = 6;
  private static final int LINE_HEIGHT = 12;
  private static final int BACKGROUND_COLOR = 0xC0101010;
  private static final int BORDER_COLOR = 0xFF303030;
  private static final int TEXT_COLOR = 0xFFFFFFFF;
  private static final int HEADER_COLOR = 0xFFFFAA00;
  private static final int UNOPENED_COLOR = 0xFF55FF55;
  private static final int OPENED_COLOR = 0xFFAAAAAA;
  private static final int BUTTON_COLOR = 0xFF5599FF;
  private static final int BUTTON_HOVER_COLOR = 0xFF77BBFF;
  private static final int PROGRESS_COLOR = 0xFFFFFF55;

  private static final int AUTO_OPEN_INTERVAL_TICKS = 5;

  // Auto-open state
  private static boolean isAutoOpening = false;
  private static int tickCounter = 0;
  private static List<Integer> slotsToOpen = new ArrayList<>();
  private static int totalToOpen = 0;
  private static int openedCount = 0;

  // Click detection
  private static int buttonX1, buttonY1, buttonX2, buttonY2;
  private static boolean buttonVisible = false;

  private PinPackOverlayRenderer() {}

  /** Called from the InventoryScreen render TAIL hook. */
  public static void renderIfVisible(
      GuiGraphicsExtractor guiGraphics,
      Screen screen,
      int containerLeft,
      int containerTop,
      int containerWidth,
      int containerHeight) {

    if (!(screen instanceof InventoryScreen)) {
      return;
    }

    if (!PimClient.isImagineFunServer()) {
      return;
    }

    Minecraft mc = Minecraft.getInstance();
    Player player = mc.player;
    if (player == null) {
      return;
    }

    Font font = mc.font;
    if (font == null) {
      return;
    }

    // Scan inventory for pin packs
    ScanResult result = scanInventory(player);

    // Don't show if no pin packs at all
    if (result.unopenedCount == 0 && result.openedCount == 0) {
      buttonVisible = false;
      return;
    }

    // Render the overlay
    render(guiGraphics, font, screen.width, screen.height, containerLeft + containerWidth, result);
  }

  /** Tick handler for auto-opening packs. Call this from a client tick event. */
  public static void tick() {
    if (!isAutoOpening) {
      return;
    }

    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || !(mc.gui.screen() instanceof InventoryScreen)) {
      stopAutoOpen();
      return;
    }

    tickCounter++;
    if (tickCounter < AUTO_OPEN_INTERVAL_TICKS) {
      return;
    }
    tickCounter = 0;

    if (slotsToOpen.isEmpty()) {
      stopAutoOpen();
      return;
    }

    // Open the next pack
    int slotNum = slotsToOpen.remove(0);
    mc.gameMode.handleContainerInput(
        mc.player.containerMenu.containerId, slotNum, 0, ContainerInput.QUICK_MOVE, mc.player);
    openedCount++;

    // Track pin box opening for daily report
    SessionTracker.getInstance().onPinBoxOpened();

    // Check if done
    if (slotsToOpen.isEmpty()) {
      stopAutoOpen();
    }
  }

  /** Handle mouse click - returns true if click was consumed. */
  public static boolean handleClick(double mouseX, double mouseY, Screen screen) {
    if (!(screen instanceof InventoryScreen)) {
      return false;
    }

    if (!buttonVisible) {
      return false;
    }

    if (mouseX >= buttonX1 && mouseX < buttonX2 && mouseY >= buttonY1 && mouseY < buttonY2) {
      if (isAutoOpening) {
        stopAutoOpen();
      } else {
        startAutoOpen();
      }
      return true;
    }

    return false;
  }

  private static void startAutoOpen() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null) {
      return;
    }

    ScanResult result = scanInventory(mc.player);
    if (result.unopenedSlots.isEmpty()) {
      return;
    }

    isAutoOpening = true;
    tickCounter = 0;
    slotsToOpen = new ArrayList<>(result.unopenedSlots);
    totalToOpen = slotsToOpen.size();
    openedCount = 0;
  }

  private static void stopAutoOpen() {
    isAutoOpening = false;
    slotsToOpen.clear();
    totalToOpen = 0;
    openedCount = 0;
  }

  private static void render(
      GuiGraphicsExtractor guiGraphics,
      Font font,
      int screenWidth,
      int screenHeight,
      int containerRight,
      ScanResult result) {

    // Calculate content
    List<String> lines = new ArrayList<>();
    lines.add("Pin Packs");
    lines.add("");
    lines.add("Unopened: " + result.unopenedCount);
    lines.add("Opened: " + result.openedCount);

    boolean showButton = result.unopenedCount > 0;
    String buttonText = isAutoOpening ? "[Stop]" : "[Open All]";
    String progressText =
        isAutoOpening ? "Opening: " + openedCount + "/" + totalToOpen + "..." : null;

    // Calculate dimensions
    int maxWidth = 0;
    for (String line : lines) {
      maxWidth = Math.max(maxWidth, font.width(line));
    }
    if (showButton) {
      maxWidth = Math.max(maxWidth, font.width(buttonText));
    }
    if (progressText != null) {
      maxWidth = Math.max(maxWidth, font.width(progressText));
    }

    int contentWidth = maxWidth;
    int tableWidth = contentWidth + PADDING * 2;

    int lineCount = lines.size();
    if (showButton) {
      lineCount += 2; // blank line + button
    }
    if (progressText != null) {
      lineCount += 1;
    }
    int tableHeight = lineCount * LINE_HEIGHT + PADDING * 2;

    // Position: right of container, vertically centered
    int gutterCenterX = (containerRight + screenWidth) / 2;
    int x = gutterCenterX - tableWidth / 2;

    // Clamp to viewport
    int minX = containerRight + 4;
    int maxX = screenWidth - tableWidth - 4;
    if (x < minX) x = minX;
    if (x > maxX) x = maxX;

    int y = (screenHeight - tableHeight) / 2;

    // Draw background
    guiGraphics.fill(x - 1, y - 1, x + tableWidth + 1, y + tableHeight + 1, BORDER_COLOR);
    guiGraphics.fill(x, y, x + tableWidth, y + tableHeight, BACKGROUND_COLOR);

    // Draw text
    int textX = x + PADDING;
    int textY = y + PADDING;

    // Header
    guiGraphics.text(font, lines.get(0), textX, textY, HEADER_COLOR, false);
    textY += LINE_HEIGHT;

    // Blank line
    textY += LINE_HEIGHT;

    // Unopened count
    guiGraphics.text(font, lines.get(2), textX, textY, UNOPENED_COLOR, false);
    textY += LINE_HEIGHT;

    // Opened count
    guiGraphics.text(font, lines.get(3), textX, textY, OPENED_COLOR, false);
    textY += LINE_HEIGHT;

    // Button
    if (showButton) {
      textY += LINE_HEIGHT; // blank line

      buttonX1 = textX;
      buttonY1 = textY;
      buttonX2 = textX + font.width(buttonText);
      buttonY2 = textY + font.lineHeight;
      buttonVisible = true;

      // Check hover
      Minecraft mc = Minecraft.getInstance();
      double mouseX =
          mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getWidth();
      double mouseY =
          mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getHeight();
      boolean hover =
          mouseX >= buttonX1 && mouseX < buttonX2 && mouseY >= buttonY1 && mouseY < buttonY2;

      guiGraphics.text(
          font, buttonText, textX, textY, hover ? BUTTON_HOVER_COLOR : BUTTON_COLOR, false);
      textY += LINE_HEIGHT;
    } else {
      buttonVisible = false;
    }

    // Progress
    if (progressText != null) {
      guiGraphics.text(font, progressText, textX, textY, PROGRESS_COLOR, false);
    }
  }

  private static ScanResult scanInventory(Player player) {
    ScanResult result = new ScanResult();

    var menu = player.containerMenu;
    int slotCount = menu.slots.size();

    for (int i = 0; i < slotCount; i++) {
      Slot slot = menu.slots.get(i);
      ItemStack stack = slot.getItem();

      if (stack.isEmpty()) {
        continue;
      }

      String name = stack.getHoverName().getString();

      // Check if it's a pin pack
      if (!name.contains("Pin Pack")) {
        continue;
      }

      CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
      if (customData == null) {
        continue;
      }

      String nbt = customData.copyTag().toString();

      // Check if opened
      if (nbt.contains("IS_PACK_OPENED:1b") || name.contains("(Opened)")) {
        result.openedCount++;
        result.openedSlots.add(i);
      } else if (nbt.contains("PIN_PACK:")) {
        result.unopenedCount++;
        result.unopenedSlots.add(i);
      }
    }

    return result;
  }

  private static class ScanResult {
    int unopenedCount = 0;
    int openedCount = 0;
    List<Integer> unopenedSlots = new ArrayList<>();
    List<Integer> openedSlots = new ArrayList<>();
  }
}
