package com.chenweikeng.imf.nra.report;

import com.chenweikeng.imf.nra.util.TimeFormatUtil;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class RideReportChatRenderer {
  private static final int HEADER_COLOR = 0xFFFFD700;
  private static final int TEXT_COLOR = 0xFFFFFFFF;
  private static final int DIM_COLOR = 0xFFAAAAAA;
  private static final int ACCENT_COLOR = 0xFF55FFFF;
  private static final int MVP_COLOR = 0xFFFFAA00;
  private static final int MILESTONE_COLOR = 0xFF55FF55;
  private static final int LIVE_COLOR = 0xFFFF5555;
  private static final int SPEED_COLOR = 0xFFFF5555;
  private static final int PIN_COLOR = 0xFFFF69B4;
  private static final int FOOD_COLOR = 0xFF8BC34A;

  private static final String DIVIDER = "━━━━━━━━━━━━━━━━━━━━━━━━━━";
  private static final int MAX_RIDE_ROWS = 5;

  private RideReportChatRenderer() {}

  public static void send(Minecraft client, DailyReport report) {
    if (client == null || client.player == null || report == null) {
      return;
    }

    sendLine(client, Component.literal(DIVIDER).withColor(HEADER_COLOR));
    sendLine(
        client,
        Component.empty()
            .append(Component.literal("✨ ").withColor(HEADER_COLOR))
            .append(
                Component.literal("IMF Ride Report")
                    .withStyle(ChatFormatting.BOLD)
                    .withColor(HEADER_COLOR))
            .append(Component.literal(" ✨").withColor(HEADER_COLOR)));

    MutableComponent gradeLine =
        Component.empty()
            .append(
                Component.literal("[" + report.grade.label + "] ")
                    .withStyle(ChatFormatting.BOLD)
                    .withColor(report.grade.color));
    if (report.isLive) {
      gradeLine.append(
          Component.literal("[LIVE] ").withStyle(ChatFormatting.BOLD).withColor(LIVE_COLOR));
    }
    gradeLine.append(
        Component.literal(formatDateFriendly(report.date))
            .withStyle(ChatFormatting.BOLD)
            .withColor(HEADER_COLOR));
    sendLine(client, gradeLine);

    sendLine(client, Component.literal("~ " + report.title + " ~").withColor(report.grade.color));

    String rideTimeStr = TimeFormatUtil.formatDuration(report.totalRideTimeSeconds);
    String onlineStr = TimeFormatUtil.formatDuration(report.totalOnlineSeconds);
    String completedVerb = report.isLive ? "You've ridden " : "You completed ";
    String rideSuffix = report.isLive ? " rides so far" : " rides";
    sendLine(
        client,
        Component.empty()
            .append(Component.literal(completedVerb).withColor(TEXT_COLOR))
            .append(
                Component.literal(report.totalRides + rideSuffix)
                    .withStyle(ChatFormatting.BOLD)
                    .withColor(ACCENT_COLOR))
            .append(Component.literal(" in ").withColor(TEXT_COLOR))
            .append(Component.literal(rideTimeStr).withColor(ACCENT_COLOR))
            .append(Component.literal(" of ride time!").withColor(TEXT_COLOR)));

    sendLine(client, Component.literal("Online for " + onlineStr).withColor(DIM_COLOR));

    if (report.previousDayRides != null) {
      boolean wasYesterday = isPreviousCalendarDay(report.date, report.previousRideDate);
      String compLabel = wasYesterday ? "yesterday" : "last ride day";
      int diff = report.totalRides - report.previousDayRides;
      Component comparison;
      if (diff > 0) {
        comparison =
            Component.literal("▲ " + diff + " more rides than " + compLabel + "!")
                .withColor(MILESTONE_COLOR);
      } else if (diff < 0) {
        comparison =
            Component.literal("▼ " + Math.abs(diff) + " fewer rides than " + compLabel)
                .withColor(DIM_COLOR);
      } else {
        comparison =
            Component.literal("= Same as " + compLabel + " — consistency!").withColor(ACCENT_COLOR);
      }
      sendLine(client, comparison);
    }

    if (report.isFirstDay) {
      sendLine(
          client,
          Component.literal("Day 1 — Baseline Recorded!")
              .withStyle(ChatFormatting.BOLD)
              .withColor(HEADER_COLOR));
      sendLine(
          client,
          Component.literal("Per-ride breakdowns start from tomorrow!").withColor(DIM_COLOR));
    }

    if (report.mvpRide != null) {
      sendLine(
          client,
          Component.empty()
              .append(Component.literal("★ MVP Ride: ").withColor(MVP_COLOR))
              .append(
                  Component.literal(report.mvpRide + " (" + report.mvpRideCount + "x)")
                      .withColor(TEXT_COLOR)));
    }

    if (report.speedDemonRide != null) {
      sendLine(
          client,
          Component.empty()
              .append(Component.literal("⚡ Speed Demon: ").withColor(SPEED_COLOR))
              .append(Component.literal(report.speedDemonRide).withColor(TEXT_COLOR)));
    }

    if (!report.newMilestones.isEmpty()) {
      sendLine(client, Component.literal("✨ New Heights Reached!").withColor(MILESTONE_COLOR));
      for (DailyReport.MilestoneReached m : report.newMilestones) {
        sendLine(
            client,
            Component.empty()
                .append(Component.literal("   • ").withColor(DIM_COLOR))
                .append(
                    Component.literal(m.ride.getDisplayName())
                        .withStyle(ChatFormatting.BOLD)
                        .withColor(MILESTONE_COLOR))
                .append(Component.literal(" reached " + m.milestone + "!").withColor(TEXT_COLOR)));
      }
    }

    if (!report.rideDeltas.isEmpty()) {
      sendLine(
          client,
          Component.literal("Top Rides").withStyle(ChatFormatting.BOLD).withColor(ACCENT_COLOR));
      int shown = 0;
      for (DailyReport.RideDelta rd : report.rideDeltas) {
        if (shown >= MAX_RIDE_ROWS) {
          break;
        }
        sendLine(
            client,
            Component.empty()
                .append(Component.literal("   ").withColor(DIM_COLOR))
                .append(Component.literal(rd.ride.getDisplayName()).withColor(TEXT_COLOR))
                .append(Component.literal("  +" + rd.countIncrease).withColor(MILESTONE_COLOR))
                .append(
                    Component.literal(
                            "  (total "
                                + rd.newTotal
                                + ", "
                                + TimeFormatUtil.formatDuration(rd.timeContributedSeconds)
                                + ")")
                        .withColor(DIM_COLOR)));
        shown++;
      }
      int remaining = report.rideDeltas.size() - shown;
      if (remaining > 0) {
        sendLine(client, Component.literal("   … and " + remaining + " more").withColor(DIM_COLOR));
      }
    }

    if (report.pinHoarderTrades > 0 || report.pinBoxesOpened > 0 || report.newMintPinsAdded > 0) {
      sendLine(
          client,
          Component.literal("Pin Activity").withStyle(ChatFormatting.BOLD).withColor(PIN_COLOR));
      if (report.pinHoarderTrades > 0) {
        sendLine(
            client,
            Component.empty()
                .append(Component.literal("   Pin Hoarder Trades: ").withColor(DIM_COLOR))
                .append(
                    Component.literal(String.valueOf(report.pinHoarderTrades))
                        .withColor(ACCENT_COLOR)));
      }
      if (report.pinBoxesOpened > 0) {
        sendLine(
            client,
            Component.empty()
                .append(Component.literal("   Pin Packs Opened: ").withColor(DIM_COLOR))
                .append(
                    Component.literal(String.valueOf(report.pinBoxesOpened))
                        .withColor(ACCENT_COLOR)));
      }
      if (report.newMintPinsAdded > 0) {
        sendLine(
            client,
            Component.empty()
                .append(Component.literal("   New Mints Added: ").withColor(DIM_COLOR))
                .append(
                    Component.literal(String.valueOf(report.newMintPinsAdded))
                        .withStyle(ChatFormatting.BOLD)
                        .withColor(MILESTONE_COLOR)));
      }
    }

    if (report.foodConsumed != null && !report.foodConsumed.isEmpty()) {
      sendLine(
          client,
          Component.literal("Food Consumed").withStyle(ChatFormatting.BOLD).withColor(FOOD_COLOR));
      for (java.util.Map.Entry<String, Integer> entry : report.foodConsumed.entrySet()) {
        sendLine(
            client,
            Component.empty()
                .append(Component.literal("   " + entry.getKey() + ": ").withColor(DIM_COLOR))
                .append(
                    Component.literal(String.valueOf(entry.getValue())).withColor(ACCENT_COLOR)));
      }
    }

    sendLine(client, Component.literal(DIVIDER).withColor(HEADER_COLOR));
  }

  private static void sendLine(Minecraft client, Component component) {
    client.player.sendSystemMessage(component);
  }

  private static String formatDateFriendly(String dateStr) {
    try {
      LocalDate d = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
      String dayOfWeek = d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
      String month = d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
      return dayOfWeek + ", " + month + " " + d.getDayOfMonth() + ", " + d.getYear();
    } catch (Exception e) {
      return dateStr;
    }
  }

  private static boolean isPreviousCalendarDay(String reportDate, String prevDate) {
    if (reportDate == null || prevDate == null) {
      return false;
    }
    try {
      LocalDate r = LocalDate.parse(reportDate, DateTimeFormatter.ISO_LOCAL_DATE);
      LocalDate p = LocalDate.parse(prevDate, DateTimeFormatter.ISO_LOCAL_DATE);
      return p.equals(r.minusDays(1));
    } catch (Exception e) {
      return false;
    }
  }
}
