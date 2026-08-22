package com.chenweikeng.imf.nra.config.profile.ui;

import com.chenweikeng.imf.nra.config.ClothConfigScreen;
import com.chenweikeng.imf.nra.config.profile.HistoryEntry;
import com.chenweikeng.imf.nra.config.profile.HistoryManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class HistoryScreen extends Screen {
  private static final int PADDING = 20;
  private static final int FOOTER_HEIGHT = 50;
  private static final int BUTTON_HEIGHT = 20;
  private static final int LABEL_COLOR = 0xFFFFFFFF;

  private final Screen parent;
  private HistoryListWidget historyList;
  private Button closeButton;

  public HistoryScreen(Screen parent) {
    super(Component.literal("Profile History"));
    this.parent = parent;
  }

  @Override
  protected void init() {
    super.init();

    int headerHeight = 30;
    int listGap = 10;
    int listY = PADDING + headerHeight + listGap;
    int listHeight = height - FOOTER_HEIGHT - PADDING - headerHeight - listGap;

    int footerY = height - FOOTER_HEIGHT + 10;
    int buttonWidth = 100;
    int startX = (width - buttonWidth) / 2;

    closeButton =
        Button.builder(Component.literal("Close"), this::onCloseClicked)
            .bounds(startX, footerY, buttonWidth, BUTTON_HEIGHT)
            .build();
    addRenderableWidget(closeButton);

    historyList =
        new HistoryListWidget(minecraft, width, listHeight, listY, this::onView, this::onApply);

    addRenderableWidget(historyList);
    historyList.setEntries(HistoryManager.getAll());
  }

  private void onView(HistoryEntry entry) {
    if (entry == null || entry.data == null) return;
    minecraft.setScreenAndShow(
        (Screen) ClothConfigScreen.createScreen(this, entry.data.copy(), () -> {}));
  }

  private void onApply(HistoryEntry entry) {
    if (entry == null) return;
    HistoryManager.applyEntry(entry);
    historyList.refreshEntries();
  }

  private void onCloseClicked(Button button) {
    onClose();
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
    renderDarkBackground(graphics);

    Component title =
        Component.literal("Profile History").withStyle(ChatFormatting.BOLD, ChatFormatting.AQUA);
    graphics.centeredText(font, title, width / 2, PADDING, LABEL_COLOR);

    int footerY = height - FOOTER_HEIGHT;
    graphics.fill(0, footerY, width, height, 0xDD000000);

    super.extractRenderState(graphics, mouseX, mouseY, delta);
  }

  private void renderDarkBackground(GuiGraphicsExtractor graphics) {
    graphics.fill(0, 0, this.width, this.height, 0xCC000000);
  }

  @Override
  public boolean shouldCloseOnEsc() {
    return true;
  }

  @Override
  public void onClose() {
    if (minecraft != null) {
      minecraft.setScreenAndShow(parent);
    }
  }
}
