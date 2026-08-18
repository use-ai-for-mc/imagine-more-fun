package com.chenweikeng.imf.alphatable;

import com.chenweikeng.imf.mixin.ImfScreenInvoker;
import java.net.URI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Renders a fixed A=1..Z=26 reference table plus a row of clickable doc links in the empty space to
 * the right of an open container GUI, vertically centered on the window. Visibility is gated on a
 * marker character in the screen title (see {@link #shouldShow(Screen)}).
 *
 * <p>The doc links are built as styled {@link Component}s with an {@link ClickEvent.OpenUrl}
 * action; clicks are routed through {@link Screen#defaultHandleClickEvent} so they trigger
 * Minecraft's standard "open this URL?" confirmation popup, exactly like chat hyperlinks.
 */
public final class AlphaTableRenderer {

  private static final int LETTER_COUNT = 26;
  private static final int COLUMNS = 3;
  private static final int PADDING = 4;
  private static final int COLUMN_GAP = 8;
  private static final int LINK_GAP = 12;
  private static final int LINK_SEPARATOR_HEIGHT = 4;
  private static final int VIEWPORT_MARGIN = 2;
  private static final int RIGHT_EDGE_MARGIN = 10;
  private static final int BACKGROUND_COLOR = 0xC0000000; // ~75% opaque black
  private static final int BORDER_COLOR = 0xFF303030;
  private static final int TEXT_COLOR = 0xFFFFFFFF;
  private static final int LINK_COLOR_RGB = 0x5599FF; // hyperlink blue (no alpha — used by Style)

  /** Marker character placed in the screen title by the server for the target window. */
  private static final String TITLE_MARKER = "丳";

  private static final Link[] LINKS = {
    makeLink(
        "Hidden Mickeys",
        "https://docs.google.com/document/d/1KF0qburbO2rL3N7eOB29RynSiMgaJK-J5VizCqxKnis/edit?usp=sharing"),
    makeLink(
        "Agent P",
        "https://docs.google.com/document/d/1ShFACMAMxXoQtERsp6C1jg0uW3dFkUG9mQkbu48b-PY/edit?usp=sharing"),
  };

  /** Screen-space rectangles of the most recently rendered link labels: [x1, y1, x2, y2]. */
  private static final int[][] LINK_RECTS = new int[LINKS.length][4];

  private static boolean linkRectsValid = false;

  private AlphaTableRenderer() {}

  private static Link makeLink(String label, String url) {
    ClickEvent click = new ClickEvent.OpenUrl(URI.create(url));
    Component component =
        Component.literal(label)
            .withStyle(
                Style.EMPTY
                    .withColor(TextColor.fromRgb(LINK_COLOR_RGB))
                    .withUnderlined(Boolean.TRUE)
                    .withClickEvent(click));
    return new Link(component, click);
  }

  /** Called from the {@code AbstractContainerScreen} render TAIL hook. */
  public static void renderIfVisible(
      GuiGraphics guiGraphics,
      Screen screen,
      int containerLeft,
      int containerTop,
      int containerWidth,
      int containerHeight) {
    if (screen == null || !shouldShow(screen)) {
      linkRectsValid = false;
      return;
    }
    Font font = Minecraft.getInstance().font;
    if (font == null) {
      linkRectsValid = false;
      return;
    }
    int containerRight = containerLeft + containerWidth;
    render(guiGraphics, font, screen.width, screen.height, containerRight);
  }

  /**
   * Returns {@code true} if a click at the given screen coords landed on one of the doc links and
   * was dispatched. Routes the matched link through the (protected) {@code
   * Screen.defaultHandleClickEvent} via {@link ImfScreenInvoker}, so vanilla's "open this URL?"
   * confirmation popup fires exactly like chat hyperlinks.
   */
  public static boolean handleClick(double mouseX, double mouseY, Screen screen) {
    if (!linkRectsValid || screen == null || !shouldShow(screen)) {
      return false;
    }
    for (int i = 0; i < LINKS.length; i++) {
      int[] r = LINK_RECTS[i];
      if (mouseX >= r[0] && mouseX < r[2] && mouseY >= r[1] && mouseY < r[3]) {
        ImfScreenInvoker.imf$defaultHandleClickEvent(
            LINKS[i].clickEvent(), Minecraft.getInstance(), screen);
        return true;
      }
    }
    return false;
  }

  /** Visibility gate — single place to refine the trigger. */
  private static boolean shouldShow(Screen screen) {
    if (!(screen instanceof AbstractContainerScreen<?>)) {
      return false;
    }
    Component title = screen.getTitle();
    return title != null && title.getString().contains(TITLE_MARKER);
  }

  private static void render(
      GuiGraphics guiGraphics, Font font, int screenWidth, int screenHeight, int containerRight) {
    String[] lines = new String[LETTER_COUNT];
    int maxCellWidth = 0;
    for (int i = 0; i < LETTER_COUNT; i++) {
      char letter = (char) ('A' + i);
      lines[i] = letter + " = " + (i + 1);
      int w = font.width(lines[i]);
      if (w > maxCellWidth) {
        maxCellWidth = w;
      }
    }

    int rows = (LETTER_COUNT + COLUMNS - 1) / COLUMNS;
    int lineHeight = font.lineHeight + 1;
    int gridContentWidth = COLUMNS * maxCellWidth + (COLUMNS - 1) * COLUMN_GAP;
    int gridHeight = rows * lineHeight;

    // Link row metrics — measure the styled components so the underline + glyph spacing match.
    int[] linkWidths = new int[LINKS.length];
    int linksTotalWidth = 0;
    for (int i = 0; i < LINKS.length; i++) {
      linkWidths[i] = font.width(LINKS[i].component());
      linksTotalWidth += linkWidths[i];
    }
    if (LINKS.length > 1) {
      linksTotalWidth += LINK_GAP * (LINKS.length - 1);
    }
    int linkRowHeight = font.lineHeight + 1;

    int contentWidth = Math.max(gridContentWidth, linksTotalWidth);
    int tableWidth = contentWidth + PADDING * 2;
    int tableHeight = gridHeight + LINK_SEPARATOR_HEIGHT + linkRowHeight + PADDING * 2;

    // Anchor against the right edge of the screen (with a margin) so popup messages
    // that appear in the center of the gutter don't cover the table.
    int x = screenWidth - tableWidth - RIGHT_EDGE_MARGIN;
    // Clamp so we never spill off the viewport or overlap the container.
    int minX = containerRight + VIEWPORT_MARGIN;
    if (x < minX) {
      x = minX;
    }
    int y = (screenHeight - tableHeight) / 2;

    // Background + 1px border.
    guiGraphics.fill(x - 1, y - 1, x + tableWidth + 1, y + tableHeight + 1, BORDER_COLOR);
    guiGraphics.fill(x, y, x + tableWidth, y + tableHeight, BACKGROUND_COLOR);

    // Letter grid (horizontally centered inside the table content area).
    int gridContentX = x + (tableWidth - gridContentWidth) / 2;
    int gridY = y + PADDING;
    for (int row = 0; row < rows; row++) {
      for (int col = 0; col < COLUMNS; col++) {
        int idx = row * COLUMNS + col;
        if (idx >= LETTER_COUNT) {
          break;
        }
        int textX = gridContentX + col * (maxCellWidth + COLUMN_GAP);
        guiGraphics.drawString(
            font, lines[idx], textX, gridY + row * lineHeight, TEXT_COLOR, false);
      }
    }

    // Link row, horizontally centered inside the table content area. Style on each Component
    // takes care of color + underline; we just hand it to drawString.
    int linkY = gridY + gridHeight + LINK_SEPARATOR_HEIGHT;
    int linkX = x + (tableWidth - linksTotalWidth) / 2;
    for (int i = 0; i < LINKS.length; i++) {
      int width = linkWidths[i];
      guiGraphics.drawString(font, LINKS[i].component(), linkX, linkY, TEXT_COLOR, false);
      LINK_RECTS[i][0] = linkX;
      LINK_RECTS[i][1] = linkY;
      LINK_RECTS[i][2] = linkX + width;
      LINK_RECTS[i][3] = linkY + font.lineHeight + 1;
      linkX += width + LINK_GAP;
    }
    linkRectsValid = true;
  }

  private record Link(Component component, ClickEvent clickEvent) {}
}
