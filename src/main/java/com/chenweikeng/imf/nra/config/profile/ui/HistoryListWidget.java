package com.chenweikeng.imf.nra.config.profile.ui;

import com.chenweikeng.imf.nra.config.profile.ConfigDiffSummary;
import com.chenweikeng.imf.nra.config.profile.HistoryEntry;
import com.chenweikeng.imf.nra.config.profile.HistoryManager;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class HistoryListWidget extends ObjectSelectionList<HistoryListWidget.Entry> {
  private static final int ENTRY_HEIGHT = 38;
  private static final int DATE_COLOR = 0xFFFFFFFF;
  private static final int SELECTED_BG_COLOR = 0x55FFFFFF;
  private static final int HOVER_BG_COLOR = 0x33FFFFFF;
  private static final int DESCRIPTION_COLOR = 0xFFAAAAAA;
  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");

  private final Consumer<HistoryEntry> onView;
  private final Consumer<HistoryEntry> onApply;

  public HistoryListWidget(
      Minecraft minecraft,
      int width,
      int height,
      int y,
      Consumer<HistoryEntry> onView,
      Consumer<HistoryEntry> onApply) {
    super(minecraft, width, height, y, ENTRY_HEIGHT);
    this.onView = onView;
    this.onApply = onApply;
  }

  public void setEntries(List<HistoryEntry> historyEntries) {
    clearEntries();
    for (HistoryEntry entry : historyEntries) {
      addEntry(new Entry(entry));
    }
  }

  public void refreshEntries() {
    setEntries(HistoryManager.getAll());
  }

  @Override
  public int getRowWidth() {
    return width - 40;
  }

  public class Entry extends ObjectSelectionList.Entry<Entry> {
    private final HistoryEntry historyEntry;
    private static final int BUTTON_WIDTH = 40;
    private static final int BUTTON_SPACING = 4;

    public Entry(HistoryEntry historyEntry) {
      this.historyEntry = historyEntry;
    }

    @Override
    public Component getNarration() {
      return Component.literal(formatDate(historyEntry.replacedAt));
    }

    @Override
    public void extractContent(
        GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
      boolean isSelected = getSelected() == this;

      int x = getContentX();
      int y = getContentY();
      int contentWidth = getContentWidth();

      if (isSelected) {
        graphics.fill(
            HistoryListWidget.this.getX(),
            getY(),
            HistoryListWidget.this.getX() + HistoryListWidget.this.width,
            getY() + getHeight(),
            SELECTED_BG_COLOR);
      } else if (hovered) {
        graphics.fill(
            HistoryListWidget.this.getX(),
            getY(),
            HistoryListWidget.this.getX() + HistoryListWidget.this.width,
            getY() + getHeight(),
            HOVER_BG_COLOR);
      }

      int textX = x + 4;
      int textY = y + 4;

      String dateStr = formatDate(historyEntry.replacedAt);
      graphics.text(minecraft.font, dateStr, textX, textY, DATE_COLOR, false);

      // Second line: brief diff description
      String desc = ConfigDiffSummary.describe(historyEntry.data);
      graphics.text(minecraft.font, desc, textX, textY + 12, DESCRIPTION_COLOR, false);

      int totalButtonsWidth = BUTTON_WIDTH * 2 + BUTTON_SPACING;
      int buttonStartX = x + contentWidth - totalButtonsWidth - 4;
      int buttonY = y + (ENTRY_HEIGHT - ButtonRenderer.BUTTON_HEIGHT) / 2;

      ButtonRenderer.renderButton(
          minecraft,
          graphics,
          mouseX,
          mouseY,
          buttonStartX,
          buttonY,
          BUTTON_WIDTH,
          "View",
          ButtonRenderer.STYLE_VIEW);
      ButtonRenderer.renderButton(
          minecraft,
          graphics,
          mouseX,
          mouseY,
          buttonStartX + BUTTON_WIDTH + BUTTON_SPACING,
          buttonY,
          BUTTON_WIDTH,
          "Apply",
          ButtonRenderer.STYLE_APPLY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      if (event.button() != 0) {
        return super.mouseClicked(event, doubleClick);
      }

      int mouseX = (int) event.x();
      int mouseY = (int) event.y();

      int contentWidth = getContentWidth();
      int totalButtonsWidth = BUTTON_WIDTH * 2 + BUTTON_SPACING;
      int buttonStartX = getContentX() + contentWidth - totalButtonsWidth - 4;
      int buttonY = getContentY() + (ENTRY_HEIGHT - ButtonRenderer.BUTTON_HEIGHT) / 2;

      if (mouseY >= buttonY && mouseY < buttonY + ButtonRenderer.BUTTON_HEIGHT) {
        if (ButtonRenderer.isMouseOver(mouseX, mouseY, buttonStartX, buttonY, BUTTON_WIDTH)) {
          if (onView != null) {
            onView.accept(historyEntry);
          }
          return true;
        }
        if (ButtonRenderer.isMouseOver(
            mouseX, mouseY, buttonStartX + BUTTON_WIDTH + BUTTON_SPACING, buttonY, BUTTON_WIDTH)) {
          if (onApply != null) {
            onApply.accept(historyEntry);
          }
          return true;
        }
      }

      return super.mouseClicked(event, doubleClick);
    }
  }

  private static String formatDate(long epochMillis) {
    return DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
  }
}
