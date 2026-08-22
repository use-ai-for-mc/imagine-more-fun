package com.chenweikeng.imf.nra.report.ui;

import com.chenweikeng.imf.nra.report.DailyRideSnapshot;
import com.chenweikeng.imf.nra.session.SessionTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class RideReportListScreen extends Screen {
  private static final int PADDING = 20;
  private static final int FOOTER_HEIGHT = 50;
  private static final int BUTTON_HEIGHT = 20;
  private static final int LABEL_COLOR = 0xFFFFFFFF;

  private final Screen parent;
  private RideReportListWidget reportList;
  private Button closeButton;

  public RideReportListScreen(Screen parent) {
    super(Component.literal("Ride Reports"));
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

    reportList = new RideReportListWidget(minecraft, width, listHeight, listY, this::onViewReport);
    addRenderableWidget(reportList);

    java.util.List<String> dates = new java.util.ArrayList<>();
    // Add live entry (null date) at the top if the session is active
    if (SessionTracker.getInstance().isActive()) {
      dates.add(null);
    }
    dates.addAll(DailyRideSnapshot.getInstance().getAvailableDates());
    reportList.setDates(dates);
  }

  private void onViewReport(String date) {
    if (date == null) {
      minecraft.setScreenAndShow(RideReportScreen.createLive(this));
    } else {
      minecraft.setScreenAndShow(new RideReportScreen(this, date));
    }
  }

  private void onCloseClicked(Button button) {
    onClose();
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
    renderDarkBackground(graphics);

    Component title =
        Component.literal("Ride Reports").withStyle(ChatFormatting.BOLD, ChatFormatting.AQUA);
    graphics.centeredText(font, title, width / 2, PADDING, LABEL_COLOR);

    if (reportList.children().isEmpty()) {
      graphics.centeredText(
          font,
          Component.literal("No reports yet! Ride some rides and check back tomorrow.")
              .withStyle(ChatFormatting.GRAY),
          width / 2,
          height / 2,
          LABEL_COLOR);
    }

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
