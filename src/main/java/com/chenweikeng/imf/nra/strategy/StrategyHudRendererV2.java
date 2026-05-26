package com.chenweikeng.imf.nra.strategy;

import com.chenweikeng.imf.mixin.NraBossHealthOverlayAccessor;
import com.chenweikeng.imf.nra.GameState;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.Timing;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.config.SortingRules;
import com.chenweikeng.imf.nra.ride.AutograbHolder;
import com.chenweikeng.imf.nra.ride.ClosestRideHolder;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.chenweikeng.imf.nra.ride.RideName;
import com.chenweikeng.imf.nra.util.TimeFormatUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;

public class StrategyHudRendererV2 {
  private static List<RideGoal> topGoals = new ArrayList<>();
  private static int updateCounter = 0;
  private static final int UPDATE_INTERVAL_TICKS = Timing.HUD_UPDATE_INTERVAL_TICKS;
  private static String currentError = null;

  private static final int COMPONENT_GAP = 20;
  private static final int COLUMN_GAP = 20;
  private static final long ANIMATION_DURATION_MS = 500;
  private static final long WAIT_DURATION_MS = 3000;

  private enum HudState {
    FULL,
    COLLAPSING,
    COLLAPSED,
    EXPANDING
  }

  private static HudState currentState = HudState.FULL;
  private static long stateStartTime = 0;
  private static RideName trackedRide = null;
  private static long normalStartTime = 0;

  private record EntryComponents(String name, String rides, String time) {}

  private record LayoutResult(
      int optimalColumns,
      List<int[]> entryXPositions,
      List<int[]> columnMaxWidths,
      int startOffset) {}

  private record LayoutCandidate(
      int numColumns, int numRows, java.util.List<int[]> columnMaxWidths) {}

  private record FullModeRenderContext(
      GuiGraphics context,
      Minecraft client,
      List<EntryComponents> entries,
      List<Integer> entryColors,
      boolean hasError,
      int numColumns,
      List<int[]> entryXPositions,
      int y,
      int lineHeight,
      int textColor,
      int errorColor) {}

  public static void update() {
    updateCounter++;
    if (updateCounter >= UPDATE_INTERVAL_TICKS) {
      updateCounter = 0;
      int displayCount = ModConfig.currentSetting.rideDisplayCount;
      topGoals = StrategyCalculator.getTopGoals(displayCount);
    }
  }

  public static void setError(String error) {
    currentError = error;
  }

  public static String getError() {
    return currentError;
  }

  public static void render(GuiGraphics context, DeltaTracker tickCounter) {
    if (!NotRidingAlertClient.isImagineFunServer()) {
      return;
    }

    Minecraft client = Minecraft.getInstance();
    if (client == null || client.gui == null) {
      return;
    }
    BossHealthOverlay bossOverlay = client.gui.getBossOverlay();
    Map<UUID, LerpingBossEvent> bossEvents =
        ((NraBossHealthOverlayAccessor) bossOverlay).getEvents();
    if (bossEvents != null && !bossEvents.isEmpty()) {
      return;
    }

    update();

    if (client == null || client.player == null || client.font == null) {
      return;
    }

    int screenWidth = client.getWindow().getGuiScaledWidth();
    int yStart = 0;
    int lineHeight = 10;
    int colorNormal = ModConfig.currentSetting.trackerNormalColor;
    int colorRiding = ModConfig.currentSetting.trackerRidingColor;
    int colorAutograbbing = ModConfig.currentSetting.trackerAutograbbingColor;
    int errorColor = ModConfig.currentSetting.trackerErrorColor;
    int colorClosest = ModConfig.currentSetting.trackerClosestRideColor;

    int displayCount = ModConfig.currentSetting.rideDisplayCount;

    RideName currentRide = CurrentRideHolder.getCurrentRide();
    RideName autograbRide = AutograbHolder.getRideAtLocation(client);
    RideName closestRide = StrategyHudBase.filterClosestRide(ClosestRideHolder.getClosestRide());
    RideName effectiveRide = currentRide != null ? currentRide : autograbRide;
    boolean isPassenger = GameState.getInstance().isValidPassenger(client.player);

    StrategyHudBase.RideStatus effectiveStatus =
        getEffectiveStatus(currentRide, autograbRide, isPassenger);
    updateState(effectiveStatus, effectiveRide);

    List<EntryComponents> entries = new ArrayList<>();
    List<Integer> entryColors = new ArrayList<>();
    boolean closestRideInList = false;

    if (displayCount > 0) {
      for (RideGoal goal : topGoals) {
        String name = goal.getRide().getShortName().toUpperCase();
        boolean isClosest =
            currentRide == null
                && autograbRide == null
                && closestRide != null
                && goal.getRide() == closestRide;

        int ridesNeeded;
        long timeNeeded;
        if (ModConfig.currentSetting.sortingRules == SortingRules.TOTAL_TIME_ASC
            || ModConfig.currentSetting.sortingRules == SortingRules.TOTAL_TIME_DESC) {
          ridesNeeded = goal.getMaxRidesNeeded();
          timeNeeded = goal.getMaxTimeNeeded();
        } else {
          ridesNeeded = goal.getNextGoalRidesNeeded();
          timeNeeded = goal.getNextGoalTimeNeeded();
        }

        if (isClosest && ridesNeeded == 0) {
          entries.add(new EntryComponents(name, "", ""));
        } else {
          entries.add(
              new EntryComponents(
                  name, ridesNeeded + "+", TimeFormatUtil.formatDuration(timeNeeded)));
        }

        if (isClosest) {
          closestRideInList = true;
          entryColors.add(colorClosest);
        } else {
          entryColors.add(colorNormal);
        }
      }

      if (!closestRideInList
          && closestRide != null
          && currentRide == null
          && autograbRide == null
          && !entries.isEmpty()) {
        RideGoal closestGoal = StrategyCalculator.getGoalForRide(closestRide);
        String name = closestRide.getShortName().toUpperCase();
        String rides;
        String time;
        if (closestGoal != null) {
          int ridesNeeded;
          long timeNeeded;
          if (ModConfig.currentSetting.sortingRules == SortingRules.TOTAL_TIME_ASC
              || ModConfig.currentSetting.sortingRules == SortingRules.TOTAL_TIME_DESC) {
            ridesNeeded = closestGoal.getMaxRidesNeeded();
            timeNeeded = closestGoal.getMaxTimeNeeded();
          } else {
            ridesNeeded = closestGoal.getNextGoalRidesNeeded();
            timeNeeded = closestGoal.getNextGoalTimeNeeded();
          }
          if (ridesNeeded == 0) {
            rides = "";
            time = "";
          } else {
            rides = ridesNeeded + "+";
            time = TimeFormatUtil.formatDuration(timeNeeded);
          }
        } else {
          rides = "";
          time = "";
        }
        entries.set(entries.size() - 1, new EntryComponents(name, rides, time));
        entryColors.set(entryColors.size() - 1, colorClosest);
      }
    }

    boolean hasError = currentError != null && !currentError.isEmpty();

    if (entries.isEmpty() && !hasError) {
      return;
    }

    LayoutResult layout = computeLayout(entries, screenWidth, client.font);
    int numColumns = layout.optimalColumns();
    java.util.List<int[]> entryXPositions = layout.entryXPositions();

    int numRows = entries.isEmpty() ? 0 : (entries.size() + numColumns - 1) / numColumns;
    int fullHeight = ((hasError ? 1 : 0) + numRows) * lineHeight;

    float animProgress = getAnimationProgress();
    int textAlpha = 255;
    int bgHeight;

    switch (currentState) {
      case FULL:
        bgHeight = fullHeight;
        break;
      case COLLAPSING:
        bgHeight = (int) (fullHeight * (1 - animProgress) + lineHeight * animProgress);
        textAlpha = (int) (255 * (1 - animProgress));
        break;
      case COLLAPSED:
        bgHeight = lineHeight;
        break;
      case EXPANDING:
        bgHeight = (int) (lineHeight * (1 - animProgress) + fullHeight * animProgress);
        textAlpha = (int) (255 * animProgress);
        break;
      default:
        bgHeight = fullHeight;
    }

    int bgY1 = yStart;
    int bgY2 = yStart + bgHeight + 4;

    int opacity = ModConfig.currentSetting.hudBackgroundOpacity;
    if (opacity > 0) {
      int alpha = (int) (opacity * 2.55);
      int bgColor = (alpha << 24);
      context.fill(0, bgY1, screenWidth, bgY2, bgColor);
    }

    int y = yStart + 2;

    switch (currentState) {
      case FULL:
        renderFullMode(
            new FullModeRenderContext(
                context,
                client,
                entries,
                entryColors,
                hasError,
                numColumns,
                entryXPositions,
                y,
                lineHeight,
                colorNormal,
                errorColor));
        break;
      case COLLAPSING:
        {
          List<Integer> fadedColors = new ArrayList<>();
          for (int c : entryColors) {
            fadedColors.add(applyAlpha(c, textAlpha));
          }
          renderFullMode(
              new FullModeRenderContext(
                  context,
                  client,
                  entries,
                  fadedColors,
                  hasError,
                  numColumns,
                  entryXPositions,
                  y,
                  lineHeight,
                  applyAlpha(colorNormal, textAlpha),
                  applyAlpha(errorColor, textAlpha)));
        }
        break;
      case COLLAPSED:
        renderCollapsedMode(
            context,
            client,
            screenWidth,
            y,
            trackedRide,
            currentRide,
            autograbRide,
            isPassenger,
            colorRiding,
            colorAutograbbing);
        break;
      case EXPANDING:
        {
          List<Integer> fadedColors = new ArrayList<>();
          for (int c : entryColors) {
            fadedColors.add(applyAlpha(c, textAlpha));
          }
          renderFullMode(
              new FullModeRenderContext(
                  context,
                  client,
                  entries,
                  fadedColors,
                  hasError,
                  numColumns,
                  entryXPositions,
                  y,
                  lineHeight,
                  applyAlpha(colorNormal, textAlpha),
                  applyAlpha(errorColor, textAlpha)));
        }
        break;
    }
  }

  private static void updateState(StrategyHudBase.RideStatus status, RideName ride) {
    long currentTime = System.currentTimeMillis();

    switch (currentState) {
      case FULL:
        if (status == StrategyHudBase.RideStatus.RIDING
            || status == StrategyHudBase.RideStatus.AUTOGRABBING) {
          currentState = HudState.COLLAPSING;
          stateStartTime = currentTime;
          trackedRide = ride;
          normalStartTime = 0;
        }
        break;
      case COLLAPSING:
        if (currentTime - stateStartTime >= ANIMATION_DURATION_MS) {
          currentState = HudState.COLLAPSED;
        }
        break;
      case COLLAPSED:
        if (status == StrategyHudBase.RideStatus.NORMAL) {
          currentState = HudState.EXPANDING;
          stateStartTime = currentTime;
          normalStartTime = 0;
        } else {
          trackedRide = ride;
          normalStartTime = 0;
        }
        break;
      case EXPANDING:
        if (status == StrategyHudBase.RideStatus.RIDING
            || status == StrategyHudBase.RideStatus.AUTOGRABBING) {
          currentState = HudState.COLLAPSED;
          trackedRide = ride;
          normalStartTime = 0;
        } else if (currentTime - stateStartTime >= ANIMATION_DURATION_MS) {
          currentState = HudState.FULL;
          trackedRide = null;
        }
        break;
    }
  }

  private static float getAnimationProgress() {
    long elapsed = System.currentTimeMillis() - stateStartTime;
    return Math.min(1.0f, (float) elapsed / ANIMATION_DURATION_MS);
  }

  private static LayoutResult computeLayout(
      List<EntryComponents> entries, int screenWidth, net.minecraft.client.gui.Font font) {

    if (entries.isEmpty()) {
      return new LayoutResult(0, List.of(), List.of(), 0);
    }

    java.util.List<Integer> nameWidths = new java.util.ArrayList<>();
    java.util.List<Integer> ridesWidths = new java.util.ArrayList<>();
    java.util.List<Integer> timeWidths = new java.util.ArrayList<>();

    for (EntryComponents entry : entries) {
      nameWidths.add(font.width(entry.name()));
      ridesWidths.add(font.width(entry.rides()));
      timeWidths.add(font.width(entry.time()));
    }

    java.util.List<LayoutCandidate> validCandidates = new java.util.ArrayList<>();

    int maxColumnsToTry = Math.min(8, entries.size());

    for (int numCols = maxColumnsToTry; numCols >= 1; numCols--) {
      java.util.List<int[]> columnMaxWidths = new java.util.ArrayList<>();

      for (int col = 0; col < numCols; col++) {
        columnMaxWidths.add(new int[] {0, 0, 0});
      }

      int entryIdx = 0;
      for (EntryComponents entry : entries) {
        int col = entryIdx % numCols;
        columnMaxWidths.get(col)[0] =
            Math.max(columnMaxWidths.get(col)[0], nameWidths.get(entryIdx));
        columnMaxWidths.get(col)[1] =
            Math.max(columnMaxWidths.get(col)[1], ridesWidths.get(entryIdx));
        columnMaxWidths.get(col)[2] =
            Math.max(columnMaxWidths.get(col)[2], timeWidths.get(entryIdx));
        entryIdx++;
      }

      int totalWidth = 0;
      for (int col = 0; col < numCols; col++) {
        int[] widths = columnMaxWidths.get(col);
        int colWidth = widths[0] + COMPONENT_GAP + widths[1] + COMPONENT_GAP + widths[2];
        if (col < numCols - 1) {
          colWidth += COLUMN_GAP;
        }
        totalWidth += colWidth;
      }

      // Always accept the 1-column layout as a fallback so that rendering
      // still works on very narrow windows where no layout fits the screen.
      if (totalWidth <= screenWidth || numCols == 1) {
        int numRows = (entries.size() + numCols - 1) / numCols;
        validCandidates.add(new LayoutCandidate(numCols, numRows, columnMaxWidths));
      }
    }

    LayoutCandidate best = validCandidates.get(0);
    for (LayoutCandidate candidate : validCandidates) {
      if (candidate.numRows() < best.numRows()
          || (candidate.numRows() == best.numRows()
              && candidate.numColumns() < best.numColumns())) {
        best = candidate;
      }
    }

    int startOffset = 0;
    int totalContentWidth = 0;
    for (int col = 0; col < best.numColumns(); col++) {
      int[] widths = best.columnMaxWidths().get(col);
      int colWidth = widths[0] + COMPONENT_GAP + widths[1] + COMPONENT_GAP + widths[2];
      if (col < best.numColumns() - 1) {
        colWidth += COLUMN_GAP;
      }
      totalContentWidth += colWidth;
    }
    startOffset = (screenWidth - totalContentWidth) / 2;

    java.util.List<int[]> optimalEntryXPositions = new java.util.ArrayList<>();

    int entryIdx = 0;
    for (EntryComponents entry : entries) {
      int col = entryIdx % best.numColumns();

      int columnStartX = startOffset;
      for (int prevCol = 0; prevCol < col; prevCol++) {
        int[] prevWidths = best.columnMaxWidths().get(prevCol);
        int prevColWidth =
            prevWidths[0] + COMPONENT_GAP + prevWidths[1] + COMPONENT_GAP + prevWidths[2];
        columnStartX += prevColWidth + COLUMN_GAP;
      }

      int nameX = columnStartX;
      int ridesX = nameX + best.columnMaxWidths().get(col)[0] + COMPONENT_GAP;
      int timeX = ridesX + best.columnMaxWidths().get(col)[1] + COMPONENT_GAP;

      optimalEntryXPositions.add(new int[] {nameX, ridesX, timeX});
      entryIdx++;
    }

    return new LayoutResult(
        best.numColumns(), optimalEntryXPositions, best.columnMaxWidths(), startOffset);
  }

  private static StrategyHudBase.RideStatus getEffectiveStatus(
      RideName currentRide, RideName autograbRide, boolean isPassenger) {
    if (currentRide != null) {
      return StrategyHudBase.RideStatus.RIDING;
    }
    if (autograbRide != null && !isPassenger) {
      return StrategyHudBase.RideStatus.AUTOGRABBING;
    }
    return StrategyHudBase.RideStatus.NORMAL;
  }

  private static void renderFullMode(FullModeRenderContext ctx) {
    GuiGraphics context = ctx.context();
    int currentY = ctx.y();

    if (ctx.hasError()) {
      String errorText = "ERROR: " + currentError;
      int errorTextWidth = ctx.client().font.width(errorText);
      int errorX = (ctx.client().getWindow().getGuiScaledWidth() - errorTextWidth) / 2;
      context.drawString(ctx.client().font, errorText, errorX, currentY, ctx.errorColor(), false);
      currentY += ctx.lineHeight();
    }

    int entryIdx = 0;
    int numRows =
        ctx.entries().isEmpty()
            ? 0
            : (ctx.entries().size() + ctx.numColumns() - 1) / ctx.numColumns();

    for (int row = 0; row < numRows; row++) {
      for (int col = 0; col < ctx.numColumns() && entryIdx < ctx.entries().size(); col++) {
        if (entryIdx >= ctx.entryXPositions().size()) {
          entryIdx++;
          continue;
        }
        int[] positions = ctx.entryXPositions().get(entryIdx);
        EntryComponents entry = ctx.entries().get(entryIdx);
        int entryColor =
            ctx.entryColors() != null && entryIdx < ctx.entryColors().size()
                ? ctx.entryColors().get(entryIdx)
                : ctx.textColor();

        context.drawString(
            ctx.client().font, entry.name(), positions[0], currentY, entryColor, false);
        context.drawString(
            ctx.client().font, entry.rides(), positions[1], currentY, entryColor, false);
        context.drawString(
            ctx.client().font, entry.time(), positions[2], currentY, entryColor, false);

        entryIdx++;
      }
      currentY += ctx.lineHeight();
    }
  }

  private static void renderCollapsedMode(
      GuiGraphics context,
      Minecraft client,
      int screenWidth,
      int y,
      RideName ride,
      RideName currentRide,
      RideName autograbRide,
      boolean isPassenger,
      int colorGreen,
      int colorPurple) {
    if (ride == null || ride == RideName.UNKNOWN) {
      return;
    }

    StrategyHudBase.RideStatus status = getRideStatus(ride, currentRide, autograbRide, isPassenger);
    int color = status == StrategyHudBase.RideStatus.AUTOGRABBING ? colorPurple : colorGreen;

    String text;
    if (status == StrategyHudBase.RideStatus.AUTOGRABBING) {
      text = "Autograbbing " + ride.getDisplayName() + "...";
    } else {
      RideGoal goal = StrategyCalculator.getGoalForRide(ride);
      Integer elapsed = CurrentRideHolder.getElapsedSeconds();
      Integer progress = CurrentRideHolder.getCurrentProgressPercent();

      StringBuilder sb = new StringBuilder();
      sb.append("Riding ");
      sb.append(ride.getDisplayName());

      if (progress != null && elapsed != null) {
        int totalSeconds = ride.getRideTime();
        int remainingSeconds = totalSeconds - elapsed;
        if (remainingSeconds < 0) {
          remainingSeconds = 0;
        }
        sb.append(" (");
        sb.append(progress);
        sb.append("% ");
        sb.append(TimeFormatUtil.formatDuration(remainingSeconds));
        sb.append(" left)");
      } else if (progress != null) {
        sb.append(" (").append(progress).append("%)");
      }

      if (goal != null) {
        if (ModConfig.currentSetting.sortingRules == SortingRules.TOTAL_TIME_ASC
            || ModConfig.currentSetting.sortingRules == SortingRules.TOTAL_TIME_DESC) {
          sb.append(" - ");
          sb.append(goal.getMaxRidesNeeded());
          sb.append(" rides (");
          sb.append(TimeFormatUtil.formatDuration(goal.getMaxTimeNeeded()));
          sb.append(") to reach ");
          sb.append(formatGoalNumber(goal.getMaxGoal()));
        } else {
          sb.append(" ");
          sb.append(goal.getNextGoalRidesNeeded());
          sb.append(" rides (");
          sb.append(TimeFormatUtil.formatDuration(goal.getNextGoalTimeNeeded()));
          sb.append(") to reach ");
          sb.append(formatGoalNumber(goal.getNextGoal()));
        }
      }

      text = sb.toString();
    }

    int textWidth = client.font.width(text);
    int x = (screenWidth - textWidth) / 2;
    context.drawString(client.font, text, x, y, color, false);
  }

  private static int applyAlpha(int color, int alpha) {
    return (color & 0x00FFFFFF) | (alpha << 24);
  }

  private static String formatGoalNumber(int goal) {
    if (goal >= 10000) {
      return (goal / 1000) + "k";
    } else if (goal >= 1000) {
      int thousands = goal / 1000;
      int hundreds = (goal % 1000) / 100;
      if (hundreds == 0) {
        return thousands + "k";
      }
      return thousands + "." + hundreds + "k";
    }
    return String.valueOf(goal);
  }

  public static List<RideGoal> getTopGoals() {
    return new ArrayList<>(topGoals);
  }

  private static StrategyHudBase.RideStatus getRideStatus(
      RideName ride, RideName currentRide, RideName autograbRide, boolean isPassenger) {
    if (currentRide != null && ride == currentRide) {
      return StrategyHudBase.RideStatus.RIDING;
    }
    if (currentRide == null && autograbRide != null && ride == autograbRide && !isPassenger) {
      return StrategyHudBase.RideStatus.AUTOGRABBING;
    }
    return StrategyHudBase.RideStatus.NORMAL;
  }
}
