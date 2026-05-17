package com.chenweikeng.imf.nra.dailyplan;

import com.chenweikeng.imf.mixin.NraBossHealthOverlayAccessor;
import com.chenweikeng.imf.nra.GameState;
import com.chenweikeng.imf.nra.ServerState;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.config.RideHubMode;
import com.chenweikeng.imf.nra.config.TrackerDisplayMode;
import com.chenweikeng.imf.nra.dailyplan.DailyPlanLayer.LayerType;
import com.chenweikeng.imf.nra.ride.AutograbHolder;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.chenweikeng.imf.nra.ride.RideCountManager;
import com.chenweikeng.imf.nra.ride.RideName;
import com.chenweikeng.imf.nra.util.TimeFormatUtil;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;

public final class DailyPlanHudRenderer {
  private static final int COLOR_TITLE = 0xFFFFD700;
  private static final int COLOR_DIM = 0xFFAAAAAA;
  private static final int COLOR_DONE = 0xFF55FF55;
  private static final int COLOR_PROGRESS = 0xFFFFAA00;
  private static final int COLOR_IDLE = 0xFFFFFFFF;
  private static final int COLOR_IDLE_GLYPH = 0xFF888888;
  private static final int COLOR_CONNECTOR = 0xFF555555;
  private static final int COLOR_BADGE = 0xFFBB88FF;
  private static final int COLOR_QUEST_GOLD = 0xFFFFD700;

  private static final int PANEL_BG = 0xB0000000;
  private static final int NODE_BG = 0x60000000;

  private static final int POWER_SPARK_COLOR = 0xFFCCFFFF;
  private static final int POWER_SPARK_WIDTH = 5;
  private static final long POWER_PERIOD_MS = 1500L;

  private static final String GLYPH_DONE = "\u25CF";
  private static final String GLYPH_PROGRESS = "\u25D0";
  private static final String GLYPH_IDLE = "\u25CB";
  private static final String ELLIPSIS = "\u2026";

  private static final int FONT_HEIGHT = 9;
  private static final int BADGE_HEIGHT = 10;
  private static final int NODE_H_PAD = 4;
  private static final int NODE_V_PAD = 3;
  private static final int NODE_HEIGHT = FONT_HEIGHT * 2 + NODE_V_PAD * 2 + 1;
  private static final int NODE_GAP = 2;
  private static final int CONNECTOR_WIDTH = 14;
  private static final int PANEL_H_PAD = 6;
  private static final int PANEL_TOP_PAD = 3;
  private static final int PANEL_BOTTOM_PAD = 4;
  private static final int WINDOW_SIZE = 5;
  private static final int WINDOW_PRIOR = 2;

  private DailyPlanHudRenderer() {}

  public static boolean isActive() {
    if (ModConfig.currentSetting.rideHubMode != RideHubMode.RIDE_PLAN) {
      return false;
    }
    if (!ServerState.isImagineFunServer()) {
      return false;
    }
    DailyPlan plan = DailyPlanManager.getInstance().getOrCreateToday();
    return plan != null && plan.layers != null && !plan.layers.isEmpty();
  }

  public static void render(GuiGraphics context, DeltaTracker tickCounter) {
    if (ModConfig.currentSetting.rideHubMode != RideHubMode.RIDE_PLAN) {
      return;
    }
    if (!ServerState.isImagineFunServer()) {
      return;
    }

    Minecraft client = Minecraft.getInstance();
    if (client == null || client.gui == null || client.player == null || client.font == null) {
      return;
    }

    TrackerDisplayMode mode = ModConfig.currentSetting.trackerDisplayMode;
    if (mode == TrackerDisplayMode.NEVER) {
      return;
    }
    boolean isRiding =
        CurrentRideHolder.getCurrentRide() != null
            || AutograbHolder.getRideAtLocation(client) != null;
    if (mode == TrackerDisplayMode.ONLY_WHEN_RIDING && !isRiding) {
      return;
    }
    if (mode == TrackerDisplayMode.ONLY_WHEN_NOT_RIDING && isRiding) {
      return;
    }

    BossHealthOverlay bossOverlay = client.gui.getBossOverlay();
    Map<UUID, LerpingBossEvent> bossEvents =
        ((NraBossHealthOverlayAccessor) bossOverlay).getEvents();
    if (bossEvents != null && !bossEvents.isEmpty()) {
      return;
    }

    DailyPlan plan = DailyPlanManager.getInstance().getOrCreateToday();
    if (plan == null || plan.layers == null || plan.layers.isEmpty()) {
      return;
    }

    renderPlan(context, client.font, client, plan);
  }

  private static void renderPlan(GuiGraphics context, Font font, Minecraft client, DailyPlan plan) {
    int screenWidth = client.getWindow().getGuiScaledWidth();

    int activeLevel = activeLevel(plan);

    String title =
        "\u2728 Ride Plan \u00B7 " + formatDateFriendly(plan.date) + " \u00B7 Level " + activeLevel;
    int titleColor = COLOR_TITLE;
    int titleWidth = font.width(title);

    RidingStatus riding = buildRidingStatus(client);
    if (riding == null) {
      riding = buildSpecialQuestNudge(plan);
    }
    int ridingWidth = riding == null ? 0 : font.width(riding.text);
    int ridingExtraHeight = riding == null ? 0 : FONT_HEIGHT + 2;

    WindowRange window = computeWindow(plan);
    boolean hasLeftEllipsis = window.start > 0;
    boolean hasRightEllipsis = window.end < plan.layers.size();

    int activeIdx = activeLevel(plan) - 1;
    RideName highlightRide = currentOrAutograbRide(client);

    List<LayerColumn> columns = new ArrayList<>();
    int maxBadgeHeight = 0;
    int maxBelowHeight = 0;
    for (int i = window.start; i < window.end; i++) {
      RideName highlightForLayer = (i == activeIdx) ? highlightRide : null;
      LayerColumn column = buildLayerColumn(font, plan, i, highlightForLayer);
      columns.add(column);
      if (column.badge != null) {
        maxBadgeHeight = BADGE_HEIGHT;
      }
      maxBelowHeight = Math.max(maxBelowHeight, column.belowBadgeHeight);
    }

    int ellipsisWidth = font.width(ELLIPSIS);
    int chainWidth = 0;
    if (hasLeftEllipsis) {
      chainWidth += ellipsisWidth + CONNECTOR_WIDTH;
    }
    for (int i = 0; i < columns.size(); i++) {
      chainWidth += columns.get(i).width;
      if (i < columns.size() - 1) {
        chainWidth += CONNECTOR_WIDTH;
      }
    }
    if (hasRightEllipsis) {
      chainWidth += CONNECTOR_WIDTH + ellipsisWidth;
    }

    int panelInnerWidth = Math.max(Math.max(chainWidth, titleWidth), ridingWidth);
    int panelWidth = panelInnerWidth + PANEL_H_PAD * 2;
    int layerAreaHeight = maxBadgeHeight + (maxBadgeHeight > 0 ? 1 : 0) + maxBelowHeight;
    int panelHeight =
        PANEL_TOP_PAD + FONT_HEIGHT + 3 + ridingExtraHeight + layerAreaHeight + PANEL_BOTTOM_PAD;
    int panelX = (screenWidth - panelWidth) / 2;
    int panelY = 0;

    context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL_BG);

    int titleX = (screenWidth - titleWidth) / 2;
    int titleY = panelY + PANEL_TOP_PAD;
    context.drawString(font, title, titleX, titleY, titleColor, false);

    int rowY = titleY + FONT_HEIGHT + 3;
    if (riding != null) {
      int ridingX = (screenWidth - ridingWidth) / 2;
      context.drawString(font, riding.text, ridingX, rowY, riding.color, false);
      rowY += FONT_HEIGHT + 2;
    }

    int firstNodeY = rowY + maxBadgeHeight + (maxBadgeHeight > 0 ? 1 : 0);
    int connectorY = firstNodeY + NODE_HEIGHT / 2;

    int chainStartX = (screenWidth - chainWidth) / 2;
    int x = chainStartX;

    if (hasLeftEllipsis) {
      context.drawString(font, ELLIPSIS, x, connectorY - FONT_HEIGHT / 2, COLOR_DIM, false);
      x += ellipsisWidth;
      // Anything off-screen to the left is done (gating invariant), so the left-ellipsis
      // connector is always a done → whatever edge — always animate.
      drawConnector(context, x, connectorY, x + CONNECTOR_WIDTH, COLOR_DONE, true);
      x += CONNECTOR_WIDTH;
    }

    for (int i = 0; i < columns.size(); i++) {
      LayerColumn column = columns.get(i);
      drawLayerColumn(context, font, column, x, firstNodeY, rowY, maxBadgeHeight);
      x += column.width;

      if (i < columns.size() - 1) {
        boolean powered = column.isDone;
        int connColor = column.isDone ? COLOR_DONE : COLOR_CONNECTOR;
        drawConnector(context, x, connectorY, x + CONNECTOR_WIDTH, connColor, powered);
        x += CONNECTOR_WIDTH;
      }
    }

    if (hasRightEllipsis) {
      int lastConnColor =
          !columns.isEmpty() && columns.get(columns.size() - 1).isDone
              ? COLOR_DONE
              : COLOR_CONNECTOR;
      drawConnector(context, x, connectorY, x + CONNECTOR_WIDTH, lastConnColor, false);
      x += CONNECTOR_WIDTH;
      context.drawString(font, ELLIPSIS, x, connectorY - FONT_HEIGHT / 2, COLOR_DIM, false);
    }
  }

  /** 1-indexed level of the first incomplete layer (or size+1 if fully complete — unusual). */
  private static int activeLevel(DailyPlan plan) {
    for (int i = 0; i < plan.layers.size(); i++) {
      if (!plan.layers.get(i).completed) {
        return i + 1;
      }
    }
    return plan.layers.size() + 1;
  }

  private static WindowRange computeWindow(DailyPlan plan) {
    int total = plan.layers.size();
    int active = total; // fallback to last
    for (int i = 0; i < total; i++) {
      if (!plan.layers.get(i).completed) {
        active = i;
        break;
      }
    }
    int start = Math.max(0, active - WINDOW_PRIOR);
    int end = Math.min(total, start + WINDOW_SIZE);
    if (end - start < WINDOW_SIZE) {
      start = Math.max(0, end - WINDOW_SIZE);
    }
    return new WindowRange(start, end);
  }

  private static LayerColumn buildLayerColumn(
      Font font, DailyPlan plan, int layerIdx, RideName highlightRide) {
    DailyPlanLayer layer = plan.layers.get(layerIdx);
    RideCountManager counts = RideCountManager.getInstance();

    LayerColumn column = new LayerColumn();
    column.isDone = layer.completed;
    if (layer.fromDailyQuest) {
      column.badge = "★ DAILY";
      column.badgeColor = COLOR_QUEST_GOLD;
    } else if (layer.type != LayerType.SINGLE) {
      column.badge = layer.type.badge();
      column.badgeColor = COLOR_BADGE;
    }
    column.badgeWidth = column.badge == null ? 0 : font.width(column.badge);

    int maxNodeWidth = column.badgeWidth;
    for (DailyPlanNode node : layer.nodes) {
      NodeLayout n = buildNodeLayout(font, plan, counts, layer, node, highlightRide);
      column.nodes.add(n);
      maxNodeWidth = Math.max(maxNodeWidth, n.width);
    }
    // All node boxes share the same width inside a column for neatness.
    for (NodeLayout n : column.nodes) {
      n.width = maxNodeWidth;
    }
    column.width = maxNodeWidth;
    column.belowBadgeHeight =
        column.nodes.size() * NODE_HEIGHT + (column.nodes.size() - 1) * NODE_GAP;
    return column;
  }

  private static NodeLayout buildNodeLayout(
      Font font,
      DailyPlan plan,
      RideCountManager counts,
      DailyPlanLayer layer,
      DailyPlanNode node,
      RideName highlightRide) {
    RideName ride = RideName.fromMatchString(node.ride);
    int progress;
    if (layer.baselineCounts != null) {
      int baseline = layer.baselineCounts.getOrDefault(node.ride, 0);
      int delta = Math.max(0, counts.getRideCount(ride) - baseline);
      progress = Math.min(delta, node.k);
    } else {
      // Future / gated layer — show no progress until it activates.
      progress = 0;
    }

    boolean isSpecialQuest = node.displayLabel != null && ride == RideName.UNKNOWN;
    NodeLayout layout = new NodeLayout();
    layout.isDone = node.completed;
    layout.blink =
        !isSpecialQuest && highlightRide != null && !layout.isDone && ride == highlightRide;
    boolean isPartial = !layout.isDone && progress > 0;

    if (layout.isDone) {
      layout.glyph = GLYPH_DONE;
      layout.glyphColor = COLOR_DONE;
      layout.nameColor = COLOR_DONE;
      layout.progColor = COLOR_DONE;
      layout.borderColor = COLOR_DONE;
    } else if (isPartial) {
      layout.glyph = GLYPH_PROGRESS;
      layout.glyphColor = COLOR_PROGRESS;
      layout.nameColor = COLOR_IDLE;
      layout.progColor = COLOR_PROGRESS;
      layout.borderColor = COLOR_PROGRESS;
    } else {
      layout.glyph = GLYPH_IDLE;
      layout.glyphColor = COLOR_IDLE_GLYPH;
      layout.nameColor = COLOR_IDLE;
      layout.progColor = COLOR_DIM;
      layout.borderColor = COLOR_IDLE_GLYPH;
    }
    if (layer.fromDailyQuest && !layout.isDone) {
      layout.glyphColor = COLOR_QUEST_GOLD;
      layout.nameColor = COLOR_QUEST_GOLD;
      layout.progColor = COLOR_QUEST_GOLD;
      layout.borderColor = COLOR_QUEST_GOLD;
    }

    if (isSpecialQuest) {
      layout.name = node.displayLabel.toUpperCase(Locale.ENGLISH);
      layout.prog = layout.isDone ? "DONE" : ("?/" + node.k);
    } else {
      layout.name = ride.getShortName().toUpperCase(Locale.ENGLISH);
      layout.prog = layout.isDone ? ("\u00D7" + node.k) : (progress + "/" + node.k);
    }

    String topRow = layout.glyph + " " + layout.name;
    layout.topRowWidth = font.width(topRow);
    layout.botRowWidth = font.width(layout.prog);
    layout.glyphWidth = font.width(layout.glyph + " ");
    layout.width = Math.max(layout.topRowWidth, layout.botRowWidth) + NODE_H_PAD * 2;
    return layout;
  }

  private static void drawLayerColumn(
      GuiGraphics context,
      Font font,
      LayerColumn column,
      int left,
      int firstNodeY,
      int badgeRowY,
      int maxBadgeHeight) {
    if (column.badge != null) {
      int badgeX = left + (column.width - column.badgeWidth) / 2;
      context.drawString(font, column.badge, badgeX, badgeRowY, column.badgeColor, false);
    }

    int y = firstNodeY;
    for (NodeLayout node : column.nodes) {
      drawNodeBox(context, font, node, left, y);
      y += NODE_HEIGHT + NODE_GAP;
    }
  }

  private static void drawNodeBox(
      GuiGraphics context, Font font, NodeLayout layout, int left, int top) {
    int right = left + layout.width;
    int bottom = top + NODE_HEIGHT;

    int borderColor = layout.blink ? blinkBorderColor() : layout.borderColor;

    context.fill(left, top, right, bottom, NODE_BG);
    context.hLine(left, right - 1, top, borderColor);
    context.hLine(left, right - 1, bottom - 1, borderColor);
    context.vLine(left, top, bottom - 1, borderColor);
    context.vLine(right - 1, top, bottom - 1, borderColor);

    int topRowStartX = left + (layout.width - layout.topRowWidth) / 2;
    int topRowY = top + NODE_V_PAD;
    context.drawString(font, layout.glyph + " ", topRowStartX, topRowY, layout.glyphColor, false);
    context.drawString(
        font, layout.name, topRowStartX + layout.glyphWidth, topRowY, layout.nameColor, false);

    int botRowX = left + (layout.width - layout.botRowWidth) / 2;
    int botRowY = topRowY + FONT_HEIGHT + 1;
    context.drawString(font, layout.prog, botRowX, botRowY, layout.progColor, false);
  }

  private static void drawConnector(
      GuiGraphics context, int left, int centerY, int right, int color, boolean powered) {
    // Base trail
    context.hLine(left, right - 1, centerY - 1, color);
    context.hLine(left, right - 1, centerY, color);

    if (!powered || right <= left) {
      return;
    }
    // Bright moving spark — "power flowing into the active layer".
    long now = System.currentTimeMillis();
    double phase = (now % POWER_PERIOD_MS) / (double) POWER_PERIOD_MS; // 0..1
    int width = right - left;
    int sparkCenter = left + (int) (phase * width);
    int sparkHalf = POWER_SPARK_WIDTH / 2;
    int sparkLeft = Math.max(left, sparkCenter - sparkHalf);
    int sparkRight = Math.min(right, sparkCenter + sparkHalf + 1);
    if (sparkLeft < sparkRight) {
      context.hLine(sparkLeft, sparkRight - 1, centerY - 1, POWER_SPARK_COLOR);
      context.hLine(sparkLeft, sparkRight - 1, centerY, POWER_SPARK_COLOR);
    }
  }

  /**
   * Time-pulsed cyan-blue border for nodes matching the ride the player is currently on. 1 second
   * period; lerps between a dim blue and a bright cyan. Recomputed every frame.
   */
  public static int blinkBorderColor() {
    long now = System.currentTimeMillis();
    double phase = (now % 1000L) / 1000.0;
    double pulse = 0.5 + 0.5 * Math.sin(phase * 2 * Math.PI);
    int r = (int) (0x33 + (0x88 - 0x33) * pulse);
    int g = (int) (0x77 + (0xCC - 0x77) * pulse);
    int b = (int) (0xCC + (0xFF - 0xCC) * pulse);
    return 0xFF000000 | (r << 16) | (g << 8) | b;
  }

  /** The ride the player is actively on (or autograbbing into), or null. */
  public static RideName currentOrAutograbRide(Minecraft client) {
    RideName currentRide = CurrentRideHolder.getCurrentRide();
    if (currentRide != null) {
      return currentRide;
    }
    RideName autograbRide = AutograbHolder.getRideAtLocation(client);
    if (autograbRide != null
        && client.player != null
        && !GameState.getInstance().isValidPassenger(client.player)) {
      return autograbRide;
    }
    return null;
  }

  private static RidingStatus buildRidingStatus(Minecraft client) {
    RideName currentRide = CurrentRideHolder.getCurrentRide();
    RideName autograbRide = AutograbHolder.getRideAtLocation(client);

    if (currentRide != null) {
      Integer progress = CurrentRideHolder.getCurrentProgressPercent();
      Integer elapsed = CurrentRideHolder.getElapsedSeconds();
      StringBuilder sb = new StringBuilder("\u25B6 ").append(currentRide.getDisplayName());
      if (progress != null && elapsed != null) {
        int remaining = Math.max(0, currentRide.getRideTime() - elapsed);
        sb.append(" \u00B7 ")
            .append(progress)
            .append("% \u00B7 ")
            .append(TimeFormatUtil.formatDuration(remaining))
            .append(" left");
      } else if (progress != null) {
        // Canoe: position-based progress, no remaining-time estimate.
        sb.append(" \u00B7 ").append(progress).append("%");
      }
      return new RidingStatus(sb.toString(), ModConfig.currentSetting.trackerRidingColor);
    }

    if (autograbRide != null && !GameState.getInstance().isValidPassenger(client.player)) {
      String text = "\u27F2 Autograbbing " + autograbRide.getDisplayName() + "\u2026";
      return new RidingStatus(text, ModConfig.currentSetting.trackerAutograbbingColor);
    }

    return null;
  }

  /**
   * Idle-only nudge shown in the same status row as the autograbbing / on-ride lines, telling the
   * user to re-open Daily Objectives once they finish a special quest. Returns null unless the
   * active layer is a special daily quest (RIDDLE / NPC / LAND) — RIDE quests auto-tick from the
   * local ride counts and don't need the prompt.
   */
  private static RidingStatus buildSpecialQuestNudge(DailyPlan plan) {
    if (plan == null || plan.layers == null) {
      return null;
    }
    for (DailyPlanLayer layer : plan.layers) {
      if (layer.completed) {
        continue;
      }
      if (!layer.fromDailyQuest || layer.nodes == null || layer.nodes.isEmpty()) {
        return null;
      }
      DailyPlanNode node = layer.nodes.get(0);
      if (node == null || node.ride == null || !isSpecialPinKey(node.ride)) {
        return null;
      }
      // White, not gold \u2014 the \u2605 DAILY badge already paints quest layers gold; a second
      // gold line
      // muddles the visual hierarchy.
      return new RidingStatus("\u2605 Open /daily after finishing the quest", COLOR_IDLE);
    }
    return null;
  }

  private static boolean isSpecialPinKey(String pinKey) {
    return pinKey.startsWith(":riddle:")
        || pinKey.startsWith(":npc:")
        || pinKey.startsWith(":land:")
        || pinKey.startsWith(":task:");
  }

  private static String formatDateFriendly(String dateStr) {
    try {
      LocalDate d = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
      return d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
          + " "
          + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
          + " "
          + d.getDayOfMonth();
    } catch (Exception e) {
      return dateStr;
    }
  }

  private record WindowRange(int start, int end) {}

  private static final class RidingStatus {
    final String text;
    final int color;

    RidingStatus(String text, int color) {
      this.text = text;
      this.color = color;
    }
  }

  private static final class LayerColumn {
    String badge;
    int badgeWidth;
    int badgeColor = COLOR_BADGE;
    boolean isDone;
    List<NodeLayout> nodes = new ArrayList<>();
    int width;
    int belowBadgeHeight;
  }

  private static final class NodeLayout {
    String glyph;
    int glyphColor;
    String name;
    int nameColor;
    String prog;
    int progColor;
    int borderColor;
    boolean isDone;
    boolean blink;
    int width;
    int topRowWidth;
    int botRowWidth;
    int glyphWidth;
  }
}
