package com.chenweikeng.imf.nra.report;

import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.config.RideReportNotifyMode;
import com.chenweikeng.imf.nra.report.ui.RideReportScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

public class RideReportNotifier {
  private static final long CHAT_REMINDER_INTERVAL_TICKS = 12000; // 10 minutes

  private static RideReportNotifier instance;

  private String pendingReportDate;
  private boolean hasViewed;
  private long ticksSinceLastReminder;
  private boolean popupScheduled;

  private RideReportNotifier() {}

  public static RideReportNotifier getInstance() {
    if (instance == null) {
      instance = new RideReportNotifier();
    }
    return instance;
  }

  public void onDateRollover(String previousDate) {
    DailyRideSnapshot snapshots = DailyRideSnapshot.getInstance();
    if (!snapshots.hasSnapshot(previousDate)) {
      return;
    }

    DailyRideSnapshot.SnapshotEntry snap = snapshots.getSnapshot(previousDate);
    if (snap == null || snap.ridesCompleted <= 0) {
      return;
    }

    pendingReportDate = previousDate;
    hasViewed = false;
    ticksSinceLastReminder = CHAT_REMINDER_INTERVAL_TICKS; // trigger immediately on first tick

    RideReportNotifyMode mode = ModConfig.currentSetting.rideReportNotifyMode;
    if (mode == RideReportNotifyMode.POPUP) {
      popupScheduled = true;
    }
  }

  public void tick() {
    if (hasViewed || pendingReportDate == null) {
      return;
    }

    Minecraft client = Minecraft.getInstance();
    if (client.player == null) {
      return;
    }

    RideReportNotifyMode mode = ModConfig.currentSetting.rideReportNotifyMode;

    if (mode == RideReportNotifyMode.POPUP && popupScheduled) {
      popupScheduled = false;
      client.execute(
          () -> {
            if (client.gui.screen() == null) {
              client.setScreenAndShow(new RideReportScreen(null, pendingReportDate));
            }
          });
      return;
    }

    if (mode == RideReportNotifyMode.CHAT) {
      ticksSinceLastReminder++;
      if (ticksSinceLastReminder >= CHAT_REMINDER_INTERVAL_TICKS) {
        ticksSinceLastReminder = 0;
        sendChatReminder(client);
      }
    }
  }

  private void sendChatReminder(Minecraft client) {
    if (client.player == null) {
      return;
    }

    Component message =
        Component.literal("§6✨ §e[IMF] §6Your daily ride report is ready! ")
            .append(
                Component.literal("§b[Click to view]")
                    .withStyle(
                        Style.EMPTY
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent.RunCommand("/ridereport"))
                            .withHoverEvent(
                                new HoverEvent.ShowText(
                                    Component.literal("View yesterday's ride report")))));

    client.player.sendSystemMessage(message);
  }

  public void markViewed() {
    hasViewed = true;
  }

  public void reset() {
    pendingReportDate = null;
    hasViewed = true;
    ticksSinceLastReminder = 0;
    popupScheduled = false;
  }
}
