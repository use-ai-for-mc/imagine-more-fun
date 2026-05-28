package com.chenweikeng.imf.pim.ui;

import com.chenweikeng.imf.pim.PimState;
import com.chenweikeng.imf.pim.pin.Algorithm;
import com.chenweikeng.imf.pim.pin.PinCalculationUtils;
import com.chenweikeng.imf.pim.pin.PinFmvCache;
import com.chenweikeng.imf.pim.pin.PinShortNameGenerator;
import com.chenweikeng.imf.pim.pin.Rarity;
import com.chenweikeng.imf.pim.screen.PinBookHandler;
import com.chenweikeng.imf.pim.screen.PinDetailHandler;
import com.chenweikeng.imf.pim.screen.PinRarityHandler;
import com.chenweikeng.imf.pim.tracker.BossBarTracker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class PimScreen extends Screen {

  private static final int SIDEBAR_WIDTH = 100;
  private static final int PADDING = 10;
  private static final int TAB_HEIGHT = 24;
  private static final int TAB_GAP = 4;
  private static final int HEADER_HEIGHT = 30;
  private static final int FOOTER_HEIGHT = 40;

  private static final int BG_COLOR = 0xCC000000;
  private static final int SIDEBAR_COLOR = 0xFF1A1A1A;
  private static final int CONTENT_COLOR = 0xFF2A2A2A;
  private static final int TAB_COLOR = 0xFF3A3A3A;
  private static final int TAB_SELECTED_COLOR = 0xFF4A7A4A;
  private static final int TAB_HOVER_COLOR = 0xFF4A4A4A;

  public enum Tab {
    TRADE("Trade"),
    COMPUTE("Compute"),
    VALUE("Value"),
    FMV("FMV"),
    EXPORT("Export"),
    RESET("Reset");

    public final String label;

    Tab(String label) {
      this.label = label;
    }
  }

  private final Screen parent;
  private Tab selectedTab = Tab.TRADE;
  private int scrollOffset = 0;
  private int maxScroll = 0;

  // Content cache
  private List<ContentLine> contentLines = new ArrayList<>();
  private boolean isLoading = false;
  private String clipboardText = null;

  // Calculation caches
  private static final ConcurrentHashMap<String, Algorithm.DPStartPoint> cachedStartPoints =
      new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, Algorithm.DPResult> cachedResults =
      new ConcurrentHashMap<>();

  private record ContentLine(Component text, int color) {
    ContentLine(String text) {
      this(Component.literal(text), 0xFFFFFFFF);
    }

    ContentLine(String text, int color) {
      this(Component.literal(text), color | 0xFF000000); // Ensure alpha is set
    }

    ContentLine(Component text) {
      this(text, 0xFFFFFFFF);
    }
  }

  public PimScreen(Screen parent) {
    super(Component.literal("Pin Inventory Manager"));
    this.parent = parent;
  }

  public PimScreen() {
    this(null);
  }

  @Override
  protected void init() {
    super.init();

    // Close button at bottom
    int closeButtonWidth = 80;
    addRenderableWidget(
        Button.builder(Component.literal("Close"), btn -> onClose())
            .bounds(
                (width - closeButtonWidth) / 2, height - FOOTER_HEIGHT + 10, closeButtonWidth, 20)
            .build());

    // Load initial content
    loadContentForTab(selectedTab);
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    // Dark background
    graphics.fill(0, 0, width, height, BG_COLOR);

    // Sidebar background
    graphics.fill(PADDING, PADDING, PADDING + SIDEBAR_WIDTH, height - FOOTER_HEIGHT, SIDEBAR_COLOR);

    // Content area background
    int contentX = PADDING + SIDEBAR_WIDTH + PADDING;
    int contentWidth = width - contentX - PADDING;
    graphics.fill(
        contentX, PADDING, contentX + contentWidth, height - FOOTER_HEIGHT, CONTENT_COLOR);

    // Header
    Component title =
        Component.literal("Pin Inventory Manager")
            .withStyle(ChatFormatting.BOLD, ChatFormatting.GOLD);
    graphics.drawCenteredString(font, title, width / 2, PADDING + 8, 0xFFFFFFFF);

    // Render tabs
    renderTabs(graphics, mouseX, mouseY);

    // Render content
    renderContent(graphics, contentX, contentWidth);

    // Render widgets (buttons)
    super.render(graphics, mouseX, mouseY, delta);
  }

  private void renderTabs(GuiGraphics graphics, int mouseX, int mouseY) {
    int tabX = PADDING + 5;
    int tabY = PADDING + HEADER_HEIGHT;
    int tabWidth = SIDEBAR_WIDTH - 10;

    for (Tab tab : Tab.values()) {
      boolean isSelected = tab == selectedTab;
      boolean isHovered =
          mouseX >= tabX
              && mouseX < tabX + tabWidth
              && mouseY >= tabY
              && mouseY < tabY + TAB_HEIGHT;

      int bgColor = isSelected ? TAB_SELECTED_COLOR : (isHovered ? TAB_HOVER_COLOR : TAB_COLOR);
      graphics.fill(tabX, tabY, tabX + tabWidth, tabY + TAB_HEIGHT, bgColor);

      // Tab label
      int textColor = isSelected ? 0xFFFFFFFF : 0xFFAAAAAA;
      graphics.drawCenteredString(font, tab.label, tabX + tabWidth / 2, tabY + 7, textColor);

      tabY += TAB_HEIGHT + TAB_GAP;
    }
  }

  private void renderContent(GuiGraphics graphics, int contentX, int contentWidth) {
    int contentY = PADDING + HEADER_HEIGHT;
    int contentHeight = height - FOOTER_HEIGHT - contentY - PADDING;

    // Enable scissor for content area
    graphics.enableScissor(contentX, contentY, contentX + contentWidth, contentY + contentHeight);

    if (isLoading) {
      graphics.drawCenteredString(
          font,
          "Loading...",
          contentX + contentWidth / 2,
          contentY + contentHeight / 2,
          0xFFFFFF00);
    } else if (contentLines.isEmpty()) {
      graphics.drawCenteredString(
          font,
          "No data available",
          contentX + contentWidth / 2,
          contentY + contentHeight / 2,
          0xFF888888);
    } else {
      int lineHeight = 12;
      int y = contentY + 5 - scrollOffset;

      for (ContentLine line : contentLines) {
        if (y + lineHeight > contentY && y < contentY + contentHeight) {
          graphics.drawString(font, line.text, contentX + 10, y, line.color, false);
        }
        y += lineHeight;
      }

      // Update max scroll
      maxScroll = Math.max(0, (contentLines.size() * lineHeight) - contentHeight + 10);
    }

    graphics.disableScissor();

    // Scroll indicator
    if (maxScroll > 0) {
      int scrollBarHeight =
          Math.max(20, contentHeight * contentHeight / (contentLines.size() * 12));
      int scrollBarY =
          contentY + (int) ((contentHeight - scrollBarHeight) * scrollOffset / (float) maxScroll);
      graphics.fill(
          contentX + contentWidth - 6,
          scrollBarY,
          contentX + contentWidth - 2,
          scrollBarY + scrollBarHeight,
          0x88FFFFFF);
    }
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    if (super.mouseClicked(event, doubleClick)) {
      return true;
    }

    if (event.button() != 0) {
      return false;
    }

    double mouseX = event.x();
    double mouseY = event.y();

    // Check tab clicks
    int tabX = PADDING + 5;
    int tabY = PADDING + HEADER_HEIGHT;
    int tabWidth = SIDEBAR_WIDTH - 10;

    for (Tab tab : Tab.values()) {
      if (mouseX >= tabX
          && mouseX < tabX + tabWidth
          && mouseY >= tabY
          && mouseY < tabY + TAB_HEIGHT) {
        if (tab != selectedTab) {
          selectedTab = tab;
          scrollOffset = 0;
          loadContentForTab(tab);
        }
        return true;
      }
      tabY += TAB_HEIGHT + TAB_GAP;
    }

    // Action lines like "[Click to Copy]", "[Click to Reset]", and
    // "[Click to Enable/Disable]" are rendered as part of the scrolling
    // content. The pre-existing Reset/Trade handlers tested fixed invisible
    // regions (no button was ever drawn there) and didn't honor scrollOffset,
    // so clicking the visible text did nothing while empty space elsewhere
    // could trigger the action — for the Reset tab that meant an accidental,
    // irreversible data wipe. Route everything through clickedActionLine().

    if (clipboardText != null && clickedActionLine("[Click to Copy", mouseX, mouseY)) {
      Minecraft.getInstance().keyboardHandler.setClipboard(clipboardText);
      // Avoid appending "Copied to clipboard!" repeatedly when the user
      // clicks several times — keep the feedback to one row instead of
      // growing contentLines (and maxScroll) unboundedly.
      String tail =
          contentLines.isEmpty() ? "" : contentLines.get(contentLines.size() - 1).text.getString();
      if (!"Copied to clipboard!".equals(tail)) {
        contentLines.add(new ContentLine("Copied to clipboard!", 0x00FF00));
      }
      return true;
    }

    if (selectedTab == Tab.RESET && clickedActionLine("[Click to Reset", mouseX, mouseY)) {
      performReset();
      return true;
    }

    if (selectedTab == Tab.TRADE && clickedActionLine("[Click to ", mouseX, mouseY)) {
      toggleTrade();
      return true;
    }

    return false;
  }

  /**
   * Returns true if the click landed on the visible portion of any contentLines entry whose text
   * starts with {@code prefix}. Computes each line's on-screen y from its index and {@code
   * scrollOffset}, then clamps the hit rectangle to the scissor region used by {@link
   * #renderContent} so clicks in scissor-hidden header/footer bands don't fire.
   */
  private boolean clickedActionLine(String prefix, double mouseX, double mouseY) {
    int contentX = PADDING + SIDEBAR_WIDTH + PADDING;
    int contentY = PADDING + HEADER_HEIGHT;
    int contentHeight = height - FOOTER_HEIGHT - contentY - PADDING;
    int lineHeight = 12;
    int textX = contentX + 10;
    int baseY = contentY + 5 - scrollOffset;

    for (int i = 0; i < contentLines.size(); i++) {
      String s = contentLines.get(i).text.getString();
      if (!s.startsWith(prefix)) {
        continue;
      }
      int y = baseY + i * lineHeight;
      int hitTop = Math.max(y, contentY);
      int hitBottom = Math.min(y + lineHeight, contentY + contentHeight);
      if (hitTop >= hitBottom) {
        continue; // fully clipped by scissor — nothing rendered
      }
      int lineW = font.width(s);
      if (mouseX >= textX && mouseX < textX + lineW && mouseY >= hitTop && mouseY < hitBottom) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    int contentX = PADDING + SIDEBAR_WIDTH + PADDING;
    int contentWidth = width - contentX - PADDING;

    if (mouseX >= contentX && mouseX < contentX + contentWidth) {
      scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (scrollY * 20)));
      return true;
    }
    return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
  }

  private void loadContentForTab(Tab tab) {
    contentLines.clear();
    clipboardText = null;
    isLoading = false;

    switch (tab) {
      case TRADE -> loadTradeContent();
      case COMPUTE -> loadComputeContent();
      case VALUE -> loadValueContent();
      case FMV -> loadFmvContent();
      case EXPORT -> loadExportContent();
      case RESET -> loadResetContent();
    }
  }

  private void loadTradeContent() {
    boolean isEnabled = PimState.isEnabled();

    contentLines.add(new ContentLine("Pin Trading Mode", 0xFFD700));
    contentLines.add(new ContentLine(""));
    contentLines.add(
        new ContentLine(
            "Status: " + (isEnabled ? "ENABLED" : "DISABLED"), isEnabled ? 0x00FF00 : 0xFF6666));
    contentLines.add(new ContentLine(""));
    contentLines.add(new ContentLine("When enabled, shows a boss bar guiding"));
    contentLines.add(new ContentLine("you to the next pin trader location."));
    contentLines.add(new ContentLine(""));
    contentLines.add(new ContentLine("Use IFone to warp to pin traders."));
    contentLines.add(new ContentLine(""));
    contentLines.add(new ContentLine("")); // Space for button
    contentLines.add(
        new ContentLine("[Click to " + (isEnabled ? "Disable" : "Enable") + "]", 0x00AAFF));
  }

  private void toggleTrade() {
    boolean newState = !PimState.isEnabled();
    PimState.setEnabled(newState);

    if (newState) {
      PimState.resetWarpPoint();
    } else {
      BossBarTracker.getInstance().disable();
    }

    loadTradeContent();
  }

  private void loadComputeContent() {
    isLoading = true;
    contentLines.add(new ContentLine("Calculating...", 0xFFFF00));

    CompletableFuture.runAsync(
        () -> {
          try {
            List<ContentLine> results = new ArrayList<>();
            results.add(new ContentLine("Expected Boxes to Complete Series", 0xFFD700));
            results.add(new ContentLine(""));

            Set<String> allSeriesNames = PinRarityHandler.getInstance().getAllSeriesNames();

            if (allSeriesNames.isEmpty()) {
              results.add(new ContentLine("No pin series data available.", 0xFF6666));
              results.add(new ContentLine("Please open /pinrarity and /pinbook first.", 0xAAAAAA));
            } else {
              double totalBoxes = 0;
              double totalPrice = 0;
              int seriesCount = 0;

              for (String seriesName : allSeriesNames) {
                if (!isSeriesValidForCalculation(seriesName)) {
                  continue;
                }

                Algorithm.PinSeriesCounts counts = getPinSeriesCounts(seriesName);
                if (counts == null) continue;

                Algorithm.DPStartPoint cachedStartPoint = cachedStartPoints.get(seriesName);
                Algorithm.DPResult result;

                if (cachedStartPoint != null && cachedStartPoint.equals(counts.startPoint)) {
                  result = cachedResults.get(seriesName);
                } else {
                  result = Algorithm.runDynamicProgramming(seriesName, counts);
                  cachedStartPoints.put(seriesName, counts.startPoint);
                  cachedResults.put(seriesName, result);
                }

                if (result == null || result.isError()) continue;

                double value = result.value.get();
                double boxes = Math.round(value / 2.0);
                // Skip already-completed series — listing "0 boxes" lines clutters
                // the view and the user only cares about what's still outstanding.
                if (boxes <= 0) continue;
                totalBoxes += boxes;
                seriesCount++;

                String priceStr = "";
                PinRarityHandler.PinSeriesEntry seriesEntry =
                    PinRarityHandler.getInstance().getSeriesEntry(seriesName);
                if (seriesEntry != null && seriesEntry.color != null) {
                  double price = boxes * seriesEntry.color.price;
                  totalPrice += price;
                  priceStr = " (" + formatPrice(price) + ")";
                }

                results.add(
                    new ContentLine(
                        seriesName + ": " + (int) boxes + " boxes" + priceStr, 0x55FF55));
              }

              if (seriesCount > 0) {
                results.add(new ContentLine(""));
                results.add(new ContentLine("─".repeat(40), 0x888888));
                String totalPriceStr = totalPrice > 0 ? " (" + formatPrice(totalPrice) + ")" : "";
                results.add(
                    new ContentLine(
                        "Total: " + (int) totalBoxes + " boxes" + totalPriceStr, 0xFFD700));
              } else {
                results.add(new ContentLine("No incomplete series found.", 0xAAAAAA));
              }
            }

            Minecraft.getInstance()
                .execute(
                    () -> {
                      contentLines = results;
                      isLoading = false;
                    });
          } catch (Exception e) {
            Minecraft.getInstance()
                .execute(
                    () -> {
                      contentLines.clear();
                      contentLines.add(new ContentLine("Error: " + e.getMessage(), 0xFF6666));
                      isLoading = false;
                    });
          }
        });
  }

  private void loadValueContent() {
    isLoading = true;
    contentLines.add(new ContentLine("Calculating...", 0xFFFF00));

    CompletableFuture.runAsync(
        () -> {
          try {
            List<ContentLine> results = new ArrayList<>();
            results.add(new ContentLine("Player-Specific Pin Values", 0xFFD700));
            results.add(new ContentLine(""));

            Map<String, Map<Rarity, Double>> allPrices = new TreeMap<>();

            for (String seriesName : PinRarityHandler.getInstance().getAllSeriesNames()) {
              PinRarityHandler.PinSeriesEntry seriesEntry =
                  PinRarityHandler.getInstance().getSeriesEntry(seriesName);

              if (seriesEntry != null
                  && seriesEntry.availability == PinRarityHandler.Availability.REQUIRED) {
                Map<Rarity, Double> prices =
                    PinCalculationUtils.calculatePlayerSpecificValuesForSeries(seriesName);
                if (!prices.isEmpty()) {
                  allPrices.put(seriesName, prices);
                }
              }
            }

            if (allPrices.isEmpty()) {
              results.add(new ContentLine("No value data available.", 0xFF6666));
            } else {
              for (Map.Entry<String, Map<Rarity, Double>> entry : allPrices.entrySet()) {
                String seriesName = entry.getKey();
                Map<Rarity, Double> prices = entry.getValue();

                results.add(new ContentLine(seriesName, 0xFFFF55));

                StringBuilder line = new StringBuilder("  ");
                for (Rarity rarity : Rarity.values()) {
                  Double price = prices.get(rarity);
                  if (price != null) {
                    line.append(rarity.name().charAt(0))
                        .append("=")
                        .append(formatPriceShort(price))
                        .append(" ");
                  }
                }
                results.add(new ContentLine(line.toString().trim(), 0x55FF55));
              }
            }

            Minecraft.getInstance()
                .execute(
                    () -> {
                      contentLines = results;
                      isLoading = false;
                    });
          } catch (Exception e) {
            Minecraft.getInstance()
                .execute(
                    () -> {
                      contentLines.clear();
                      contentLines.add(new ContentLine("Error: " + e.getMessage(), 0xFF6666));
                      isLoading = false;
                    });
          }
        });
  }

  private void loadFmvContent() {
    isLoading = true;
    contentLines.add(new ContentLine("Calculating...", 0xFFFF00));

    CompletableFuture.runAsync(
        () -> {
          try {
            List<ContentLine> results = new ArrayList<>();
            results.add(new ContentLine("Fair Market Value (FMV)", 0xFFD700));
            results.add(new ContentLine(""));

            Map<String, PinCalculationUtils.FMVResult> allResults = new TreeMap<>();

            for (String seriesName : PinRarityHandler.getInstance().getAllSeriesNames()) {
              PinRarityHandler.PinSeriesEntry seriesEntry =
                  PinRarityHandler.getInstance().getSeriesEntry(seriesName);

              if (seriesEntry != null
                  && seriesEntry.availability == PinRarityHandler.Availability.REQUIRED) {
                PinCalculationUtils.FMVResult fmvResult =
                    PinCalculationUtils.calculateFMVValuesForSeries(seriesName);
                if (!fmvResult.floorValues.isEmpty() || !fmvResult.ceilValues.isEmpty()) {
                  allResults.put(seriesName, fmvResult);
                }
              }
            }

            if (allResults.isEmpty()) {
              results.add(new ContentLine("No FMV data available.", 0xFF6666));
            } else {
              StringBuilder clipboard = new StringBuilder();
              PinShortNameGenerator shortNameGenerator = PinShortNameGenerator.getInstance();
              shortNameGenerator.generateShortNames();

              for (Map.Entry<String, PinCalculationUtils.FMVResult> entry : allResults.entrySet()) {
                String seriesName = entry.getKey();
                PinCalculationUtils.FMVResult fmvResult = entry.getValue();
                String shortName = shortNameGenerator.getSeriesShortName(seriesName);

                results.add(new ContentLine(seriesName, 0xFFFF55));

                StringBuilder line = new StringBuilder("  ");
                StringBuilder clipLine = new StringBuilder(shortName + ":");

                for (Rarity rarity : Rarity.values()) {
                  Double floor = fmvResult.floorValues.get(rarity);
                  Double ceil = fmvResult.ceilValues.get(rarity);
                  if (floor != null || ceil != null) {
                    int f = floor != null ? (int) Math.round(floor) : 0;
                    int c = ceil != null ? (int) Math.round(ceil) : 0;
                    String range = (f == c) ? String.valueOf(f) : f + "-" + c;
                    String abbr = String.valueOf(rarity.name().charAt(0));

                    line.append(abbr).append("=").append(range).append(" ");
                    clipLine.append(" ").append(abbr).append("=").append(range).append(",");
                  }
                }

                results.add(new ContentLine(line.toString().trim(), 0x55FF55));

                if (clipLine.charAt(clipLine.length() - 1) == ',') {
                  clipLine.setLength(clipLine.length() - 1);
                }
                clipboard.append(clipLine).append("\n");
              }

              final String clipboardFinal = clipboard.toString();
              Minecraft.getInstance()
                  .execute(
                      () -> {
                        clipboardText = clipboardFinal;
                      });

              results.add(new ContentLine(""));
              results.add(new ContentLine("[Click to Copy All]", 0x00AAFF));
            }

            Minecraft.getInstance()
                .execute(
                    () -> {
                      contentLines = results;
                      isLoading = false;
                    });
          } catch (Exception e) {
            Minecraft.getInstance()
                .execute(
                    () -> {
                      contentLines.clear();
                      contentLines.add(new ContentLine("Error: " + e.getMessage(), 0xFF6666));
                      isLoading = false;
                    });
          }
        });
  }

  private void loadExportContent() {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null) {
      contentLines.add(new ContentLine("Error: Player not found", 0xFF6666));
      return;
    }

    String playerName = mc.player.getName().getString();
    PinShortNameGenerator shortNameGenerator = PinShortNameGenerator.getInstance();
    shortNameGenerator.generateShortNames();

    contentLines.add(new ContentLine("Export Pins", 0xFFD700));
    contentLines.add(new ContentLine(""));

    StringBuilder exportText = new StringBuilder();
    exportText.append("**Player:** ").append(playerName);

    // Looking for pins
    Map<String, List<String>> lookingFor = getLookingForPins(shortNameGenerator);
    if (!lookingFor.isEmpty()) {
      exportText.append("\n\n:lookingfor:\n");
      contentLines.add(new ContentLine("Looking For:", 0xFF6666));
      for (Map.Entry<String, List<String>> entry : lookingFor.entrySet()) {
        String shortSeriesName = shortNameGenerator.getSeriesShortName(entry.getKey());
        String pins = String.join(" ", entry.getValue());
        contentLines.add(new ContentLine("  " + shortSeriesName + ": " + pins, 0xFFAAAA));
        exportText.append("- ").append(shortSeriesName).append(": ").append(pins).append("\n");
      }
    }

    // For sale pins
    Map<String, List<String>> forSale = getForSalePins(shortNameGenerator);
    if (!forSale.isEmpty()) {
      if (!lookingFor.isEmpty()) {
        exportText.append("\n");
        contentLines.add(new ContentLine(""));
      }
      exportText.append(":forsale:\n");
      contentLines.add(new ContentLine("For Sale:", 0x55FF55));
      for (Map.Entry<String, List<String>> entry : forSale.entrySet()) {
        String shortSeriesName = shortNameGenerator.getSeriesShortName(entry.getKey());
        String pins = String.join(" ", entry.getValue());
        contentLines.add(new ContentLine("  " + shortSeriesName + ": " + pins, 0xAAFFAA));
        exportText.append("- ").append(shortSeriesName).append(": ").append(pins).append("\n");
      }
    }

    if (lookingFor.isEmpty() && forSale.isEmpty()) {
      contentLines.add(new ContentLine("No pins to export.", 0xAAAAAA));
    } else {
      clipboardText = exportText.toString();
      contentLines.add(new ContentLine(""));
      contentLines.add(new ContentLine("[Click to Copy]", 0x00AAFF));
    }
  }

  private void loadResetContent() {
    contentLines.add(new ContentLine("Reset All Pin Data", 0xFFD700));
    contentLines.add(new ContentLine(""));
    contentLines.add(new ContentLine("This will reset:", 0xFFFFFF));
    contentLines.add(new ContentLine("  - Pin rarity data", 0xAAAAAA));
    contentLines.add(new ContentLine("  - Pin book data", 0xAAAAAA));
    contentLines.add(new ContentLine("  - Pin detail data", 0xAAAAAA));
    contentLines.add(new ContentLine("  - FMV cache", 0xAAAAAA));
    contentLines.add(new ContentLine(""));
    contentLines.add(new ContentLine("WARNING: This cannot be undone!", 0xFF6666));
    contentLines.add(new ContentLine(""));
    contentLines.add(new ContentLine("[Click to Reset]", 0xFF4444));
  }

  private void performReset() {
    PinRarityHandler.getInstance().reset();
    PinBookHandler.getInstance().reset();
    PinDetailHandler.getInstance().reset();
    PinFmvCache.resetCache();

    contentLines.clear();
    contentLines.add(new ContentLine("Reset Complete", 0xFFD700));
    contentLines.add(new ContentLine(""));
    contentLines.add(new ContentLine("All pin data has been reset.", 0x55FF55));
  }

  // Helper methods from existing commands

  private boolean isSeriesValidForCalculation(String seriesName) {
    Map<String, PinDetailHandler.PinDetailEntry> detailMap =
        PinDetailHandler.getInstance().getSeriesDetails(seriesName);
    if (detailMap == null || detailMap.isEmpty()) return false;

    PinBookHandler.PinBookEntry bookEntry = PinBookHandler.getInstance().getBookEntry(seriesName);
    if (bookEntry == null) return false;
    if (detailMap.size() != bookEntry.totalMints) return false;
    if (bookEntry.totalMints == bookEntry.mintsCollected) return false;

    for (PinDetailHandler.PinDetailEntry entry : detailMap.values()) {
      if (entry.rarity == null) return false;
    }

    PinRarityHandler.PinSeriesEntry seriesEntry =
        PinRarityHandler.getInstance().getSeriesEntry(seriesName);
    return seriesEntry != null
        && seriesEntry.availability == PinRarityHandler.Availability.REQUIRED;
  }

  private Algorithm.PinSeriesCounts getPinSeriesCounts(String seriesName) {
    Map<String, PinDetailHandler.PinDetailEntry> detailMap =
        PinDetailHandler.getInstance().getSeriesDetails(seriesName);
    if (detailMap == null || detailMap.isEmpty()) return null;

    int signature = 0, deluxe = 0, rare = 0, uncommon = 0, common = 0;
    int mintSig = 0, mintDel = 0, mintRare = 0, mintUnc = 0, mintCom = 0;

    for (PinDetailHandler.PinDetailEntry entry : detailMap.values()) {
      if (entry.rarity == null) continue;
      boolean isMint = entry.condition == PinDetailHandler.PinCondition.MINT;

      switch (entry.rarity) {
        case SIGNATURE -> {
          signature++;
          if (isMint) mintSig++;
        }
        case DELUXE -> {
          deluxe++;
          if (isMint) mintDel++;
        }
        case RARE -> {
          rare++;
          if (isMint) mintRare++;
        }
        case UNCOMMON -> {
          uncommon++;
          if (isMint) mintUnc++;
        }
        case COMMON -> {
          common++;
          if (isMint) mintCom++;
        }
      }
    }

    Algorithm.DPGoal goal = new Algorithm.DPGoal(signature, deluxe, rare, uncommon, common);
    Algorithm.DPStartPoint startPoint =
        new Algorithm.DPStartPoint(mintSig, mintDel, mintRare, mintUnc, mintCom);
    return new Algorithm.PinSeriesCounts(goal, startPoint);
  }

  private Map<String, List<String>> getLookingForPins(PinShortNameGenerator shortNameGenerator) {
    Map<String, List<String>> result = new TreeMap<>();
    Set<String> playerMintPins = getPlayerDetailMintPins();

    for (String seriesName : PinRarityHandler.getInstance().getAllSeriesNames()) {
      PinRarityHandler.PinSeriesEntry seriesEntry =
          PinRarityHandler.getInstance().getSeriesEntry(seriesName);
      if (seriesEntry == null || seriesEntry.availability != PinRarityHandler.Availability.REQUIRED)
        continue;

      Map<String, PinDetailHandler.PinDetailEntry> detailMap =
          PinDetailHandler.getInstance().getSeriesDetails(seriesName);
      if (detailMap == null || detailMap.isEmpty()) continue;

      List<String> missing = new ArrayList<>();
      for (String pinName : detailMap.keySet()) {
        if (!playerMintPins.contains(seriesName + ":" + pinName)) {
          missing.add(shortNameGenerator.getShortName(seriesName, pinName));
        }
      }
      if (!missing.isEmpty()) result.put(seriesName, missing);
    }
    return result;
  }

  private Map<String, List<String>> getForSalePins(PinShortNameGenerator shortNameGenerator) {
    Map<String, List<String>> result = new TreeMap<>();
    Set<String> inventoryMintPins = com.chenweikeng.imf.pim.pin.Utils.getPlayerInventoryMintPins();

    for (String pinKey : inventoryMintPins) {
      String[] parts = pinKey.split(":");
      if (parts.length == 2) {
        result
            .computeIfAbsent(parts[0], k -> new ArrayList<>())
            .add(shortNameGenerator.getShortName(parts[0], parts[1]));
      }
    }
    return result;
  }

  private Set<String> getPlayerDetailMintPins() {
    Set<String> mintPins = new java.util.HashSet<>();
    PinDetailHandler handler = PinDetailHandler.getInstance();

    for (String seriesName : handler.getAllSeriesNames()) {
      Map<String, PinDetailHandler.PinDetailEntry> seriesPins =
          handler.getSeriesDetails(seriesName);
      if (seriesPins != null) {
        for (Map.Entry<String, PinDetailHandler.PinDetailEntry> entry : seriesPins.entrySet()) {
          if (entry.getValue().condition == PinDetailHandler.PinCondition.MINT) {
            mintPins.add(seriesName + ":" + entry.getKey());
          }
        }
      }
    }
    return mintPins;
  }

  private String formatPrice(double value) {
    if (value >= 1_000_000) return String.format("%.1fM", value / 1_000_000);
    if (value >= 1_000) return String.format("%.1fK", value / 1_000);
    return String.format("%.0f", value);
  }

  private String formatPriceShort(double value) {
    if (value == Math.floor(value)) return String.format("%.0f", value);
    return String.format("%.1f", value);
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
