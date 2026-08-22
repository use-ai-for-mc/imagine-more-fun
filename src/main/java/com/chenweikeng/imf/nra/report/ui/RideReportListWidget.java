package com.chenweikeng.imf.nra.report.ui;

import com.chenweikeng.imf.nra.config.profile.ui.ButtonRenderer;
import com.chenweikeng.imf.nra.report.DailyReport;
import com.chenweikeng.imf.nra.report.DailyRideSnapshot;
import com.chenweikeng.imf.nra.session.SessionTracker;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class RideReportListWidget extends ObjectSelectionList<RideReportListWidget.Entry> {
  private static final int ENTRY_HEIGHT = 32;
  private static final int DATE_COLOR = 0xFFFFFFFF;
  private static final int SELECTED_BG_COLOR = 0x55FFFFFF;
  private static final int HOVER_BG_COLOR = 0x33FFFFFF;
  private static final int SUMMARY_COLOR = 0xFFAAAAAA;

  private final Consumer<String> onView;

  public RideReportListWidget(
      Minecraft minecraft, int width, int height, int y, Consumer<String> onView) {
    super(minecraft, width, height, y, ENTRY_HEIGHT);
    this.onView = onView;
  }

  public void setDates(List<String> dates) {
    clearEntries();
    for (String date : dates) {
      addEntry(new Entry(date));
    }
  }

  @Override
  public int getRowWidth() {
    return width - 40;
  }

  public class Entry extends ObjectSelectionList.Entry<Entry> {
    private final String date;
    private static final int BUTTON_WIDTH = 40;

    public Entry(String date) {
      this.date = date;
    }

    @Override
    public Component getNarration() {
      return Component.literal(date);
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
            RideReportListWidget.this.getX(),
            getY(),
            RideReportListWidget.this.getX() + RideReportListWidget.this.width,
            getY() + getHeight(),
            SELECTED_BG_COLOR);
      } else if (hovered) {
        graphics.fill(
            RideReportListWidget.this.getX(),
            getY(),
            RideReportListWidget.this.getX() + RideReportListWidget.this.width,
            getY() + getHeight(),
            HOVER_BG_COLOR);
      }

      int textX = x + 4;
      int textY = y + 4;

      // Date display with grade badge
      Component dateComp;
      String summary;
      if (date == null) {
        // Live entry
        SessionTracker session = SessionTracker.getInstance();
        int rides = session.getRidesToday();
        long rideTime = session.getRideTimeToday();
        DailyReport.Grade grade = DailyReport.Grade.bestOf(rides, rideTime);
        dateComp =
            Component.empty()
                .append(Component.literal("[" + grade.label + "] ").withColor(grade.color))
                .append(Component.literal("[LIVE] ").withColor(0xFFFF5555))
                .append(Component.literal("Today").withColor(DATE_COLOR));
        summary = rides + " rides so far";
      } else {
        DailyRideSnapshot.SnapshotEntry snap = DailyRideSnapshot.getInstance().getSnapshot(date);
        DailyReport.Grade grade = null;
        summary = "";
        if (snap != null) {
          grade = DailyReport.Grade.bestOf(snap.ridesCompleted, snap.totalRideTimeSeconds);
          summary = snap.ridesCompleted + " rides";
        }
        dateComp = Component.literal(formatDateFriendly(date)).withColor(DATE_COLOR);
        if (grade != null) {
          dateComp =
              Component.empty()
                  .append(Component.literal("[" + grade.label + "] ").withColor(grade.color))
                  .append(Component.literal(formatDateFriendly(date)).withColor(DATE_COLOR));
        }
      }
      graphics.text(minecraft.font, dateComp, textX, textY, DATE_COLOR, false);

      // Second line: summary
      graphics.text(
          minecraft.font,
          Component.literal(summary).withColor(SUMMARY_COLOR),
          textX,
          textY + 12,
          SUMMARY_COLOR,
          false);

      // View button
      int buttonX = x + contentWidth - BUTTON_WIDTH - 4;
      int buttonY = y + (ENTRY_HEIGHT - ButtonRenderer.BUTTON_HEIGHT) / 2;
      ButtonRenderer.renderButton(
          minecraft,
          graphics,
          mouseX,
          mouseY,
          buttonX,
          buttonY,
          BUTTON_WIDTH,
          "View",
          ButtonRenderer.STYLE_VIEW);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      if (event.button() != 0) {
        return super.mouseClicked(event, doubleClick);
      }

      int mouseX = (int) event.x();
      int mouseY = (int) event.y();

      int contentWidth = getContentWidth();
      int buttonX = getContentX() + contentWidth - BUTTON_WIDTH - 4;
      int buttonY = getContentY() + (ENTRY_HEIGHT - ButtonRenderer.BUTTON_HEIGHT) / 2;

      if (ButtonRenderer.isMouseOver(mouseX, mouseY, buttonX, buttonY, BUTTON_WIDTH)) {
        if (onView != null) {
          onView.accept(date);
        }
        return true;
      }

      if (doubleClick && onView != null) {
        onView.accept(date);
        return true;
      }

      return super.mouseClicked(event, doubleClick);
    }

    private String formatDateFriendly(String dateStr) {
      try {
        LocalDate d = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
        String dayOfWeek = d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        String month = d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        return dayOfWeek + ", " + month + " " + d.getDayOfMonth() + ", " + d.getYear();
      } catch (Exception e) {
        return dateStr;
      }
    }
  }
}
