package com.chenweikeng.imf.nra.strategy;

import com.chenweikeng.imf.nra.GameState;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.Timing;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.ride.AutograbHolder;
import com.chenweikeng.imf.nra.ride.ClosestRideHolder;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.chenweikeng.imf.nra.ride.RideName;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Strategy HUD v0 — top-left anchored, single/two-column toggle at 8+ goals.
 *
 * <p>Shared formatting and helpers live in {@link StrategyHudBase}; this class only owns the
 * V0-specific positioning, the single-vs-two-column layout decision, and the per-version static
 * state.
 */
public class StrategyHudRendererV0 {
  private static List<RideGoal> topGoals = new ArrayList<>();
  private static int updateCounter = 0;
  private static final int UPDATE_INTERVAL_TICKS = Timing.HUD_UPDATE_INTERVAL_TICKS;
  private static String currentError = null;

  // ---- public API (called by Dispatcher) ----

  public static void update() {
    updateCounter++;
    if (updateCounter >= UPDATE_INTERVAL_TICKS) {
      updateCounter = 0;
      topGoals = StrategyCalculator.getTopGoals(ModConfig.currentSetting.rideDisplayCount);
    }
  }

  public static void setError(String error) {
    currentError = error;
  }

  public static String getError() {
    return currentError;
  }

  public static List<RideGoal> getTopGoals() {
    return new ArrayList<>(topGoals);
  }

  // ---- render ----

  public static void render(GuiGraphics context, DeltaTracker tickCounter) {
    if (!NotRidingAlertClient.isImagineFunServer()) {
      return;
    }

    Minecraft client = Minecraft.getInstance();
    if (client == null || client.player == null || client.font == null) {
      return;
    }

    update();

    int screenWidth = client.getWindow().getGuiScaledWidth();
    int xLeft = 50;
    int xRight = screenWidth - 50;
    int yStart = 50;
    int lineHeight = 10;

    int colorNormal = ModConfig.currentSetting.trackerNormalColor;
    int colorRiding = ModConfig.currentSetting.trackerRidingColor;
    int colorAutograbbing = ModConfig.currentSetting.trackerAutograbbingColor;
    int colorClosest = ModConfig.currentSetting.trackerClosestRideColor;
    int errorColor = ModConfig.currentSetting.trackerErrorColor;

    int displayCount = ModConfig.currentSetting.rideDisplayCount;

    RideName currentRide = CurrentRideHolder.getCurrentRide();
    RideName autograbRide = AutograbHolder.getRideAtLocation(client);
    RideName closestRide = StrategyHudBase.filterClosestRide(ClosestRideHolder.getClosestRide());
    RideName effectiveRide =
        currentRide != null ? currentRide : (autograbRide != null ? autograbRide : closestRide);
    boolean currentRideInTop =
        effectiveRide != null && topGoals.stream().anyMatch(g -> g.getRide() == effectiveRide);
    boolean isPassenger = GameState.getInstance().isValidPassenger(client.player);

    int topGoalsSize = topGoals.size();
    boolean wantsTwoColumns = topGoalsSize >= 8;
    int availableWidth = xRight - xLeft;
    int gap = 10;
    List<RideGoal> goalsForFit = displayCount > 0 ? topGoals : List.of();

    StrategyHudBase.LayoutInput layoutInput =
        new StrategyHudBase.LayoutInput(
            client,
            goalsForFit,
            currentRide,
            autograbRide,
            closestRide,
            effectiveRide,
            currentRideInTop,
            isPassenger,
            currentError,
            availableWidth,
            gap);

    StrategyHudBase.LayoutDecision decision =
        decideLayout(layoutInput, ModConfig.currentSetting.displayShortName, wantsTwoColumns);
    if (!decision.visible()) {
      return;
    }
    boolean useShortNames = decision.useShortNames();
    boolean twoColumns = decision.twoColumns();

    List<RideGoal> leftGoals;
    List<RideGoal> rightGoals;
    if (displayCount > 0) {
      if (!twoColumns) {
        leftGoals = topGoals;
        rightGoals = List.of();
      } else {
        int leftCount = (topGoalsSize + 1) / 2;
        leftGoals = topGoals.subList(0, Math.min(leftCount, topGoalsSize));
        rightGoals =
            topGoalsSize > leftCount ? topGoals.subList(leftCount, topGoalsSize) : List.of();
      }
    } else {
      leftGoals = List.of();
      rightGoals = List.of();
    }

    int maxColumnHeight = Math.max(leftGoals.size(), rightGoals.size());

    boolean hasError = currentError != null && !currentError.isEmpty();
    boolean hasExtraRide =
        effectiveRide != null && effectiveRide != RideName.UNKNOWN && !currentRideInTop;

    int y = yStart;

    if (hasError) {
      context.drawString(client.font, "ERROR: " + currentError, xLeft, y, errorColor, false);
      y += lineHeight;
    }

    if (displayCount > 0) {
      renderColumn(
          context,
          client,
          leftGoals,
          currentRide,
          autograbRide,
          closestRide,
          useShortNames,
          isPassenger,
          colorNormal,
          colorRiding,
          colorAutograbbing,
          colorClosest,
          xLeft,
          y,
          lineHeight);
      if (twoColumns) {
        renderColumnRight(
            context,
            client,
            rightGoals,
            currentRide,
            autograbRide,
            closestRide,
            useShortNames,
            isPassenger,
            colorNormal,
            colorRiding,
            colorAutograbbing,
            colorClosest,
            xRight,
            y,
            lineHeight);
      }
    }

    if (hasExtraRide) {
      int extraY = yStart + ((hasError ? 1 : 0) + maxColumnHeight + 1) * lineHeight;
      renderExtraRide(
          context,
          client,
          effectiveRide,
          currentRide,
          autograbRide,
          closestRide,
          useShortNames,
          isPassenger,
          colorNormal,
          colorRiding,
          colorAutograbbing,
          colorClosest,
          xLeft,
          extraY);
    }
  }

  // ---- V0-specific helpers ----

  private static void renderColumn(
      GuiGraphics context,
      Minecraft client,
      List<RideGoal> goals,
      RideName currentRide,
      RideName autograbRide,
      RideName closestRide,
      boolean useShortNames,
      boolean isPassenger,
      int colorNormal,
      int colorRiding,
      int colorAutograbbing,
      int colorClosest,
      int x,
      int y,
      int lineHeight) {
    for (int i = 0; i < goals.size(); i++) {
      RideGoal goal = goals.get(i);
      StrategyHudBase.FormattedRide fr =
          StrategyHudBase.formatRideName(
              goal.getRide(), currentRide, autograbRide, closestRide, useShortNames, isPassenger);
      String text = StrategyHudBase.formatGoalText(fr, goal, ModConfig.currentSetting.sortingRules);
      int color =
          StrategyHudBase.getColorForStatus(
              fr.getStatus(), colorNormal, colorRiding, colorAutograbbing, colorClosest);
      context.drawString(client.font, text, x, y + (i * lineHeight), color, false);
    }
  }

  private static void renderColumnRight(
      GuiGraphics context,
      Minecraft client,
      List<RideGoal> goals,
      RideName currentRide,
      RideName autograbRide,
      RideName closestRide,
      boolean useShortNames,
      boolean isPassenger,
      int colorNormal,
      int colorRiding,
      int colorAutograbbing,
      int colorClosest,
      int xRight,
      int y,
      int lineHeight) {
    for (int i = 0; i < goals.size(); i++) {
      RideGoal goal = goals.get(i);
      StrategyHudBase.FormattedRide fr =
          StrategyHudBase.formatRideName(
              goal.getRide(), currentRide, autograbRide, closestRide, useShortNames, isPassenger);
      String text = StrategyHudBase.formatGoalText(fr, goal, ModConfig.currentSetting.sortingRules);
      int color =
          StrategyHudBase.getColorForStatus(
              fr.getStatus(), colorNormal, colorRiding, colorAutograbbing, colorClosest);
      int textWidth = client.font.width(text);
      context.drawString(client.font, text, xRight - textWidth, y + (i * lineHeight), color, false);
    }
  }

  private static void renderExtraRide(
      GuiGraphics context,
      Minecraft client,
      RideName ride,
      RideName currentRide,
      RideName autograbRide,
      RideName closestRide,
      boolean useShortNames,
      boolean isPassenger,
      int colorNormal,
      int colorRiding,
      int colorAutograbbing,
      int colorClosest,
      int x,
      int y) {
    RideGoal currentGoal = StrategyCalculator.getGoalForRide(ride);
    StrategyHudBase.FormattedRide fr =
        StrategyHudBase.formatRideName(
            ride, currentRide, autograbRide, closestRide, useShortNames, isPassenger);
    String text;
    if (currentGoal != null) {
      text = StrategyHudBase.formatGoalText(fr, currentGoal, ModConfig.currentSetting.sortingRules);
    } else if (fr.getStatus() == StrategyHudBase.RideStatus.RIDING) {
      text = "Riding: " + fr.getName();
    } else {
      text = fr.getName();
    }
    int color =
        StrategyHudBase.getColorForStatus(
            fr.getStatus(), colorNormal, colorRiding, colorAutograbbing, colorClosest);
    context.drawString(client.font, text, x, y, color, false);
  }

  // ---- V0-specific layout (single/two-column toggle) ----

  private static StrategyHudBase.LayoutDecision decideLayout(
      StrategyHudBase.LayoutInput layoutInput, boolean baseUseShortNames, boolean wantsTwoColumns) {
    boolean useShortNames = baseUseShortNames;
    boolean twoColumns = wantsTwoColumns;
    if (fitsLayout(layoutInput, useShortNames, twoColumns)) {
      return new StrategyHudBase.LayoutDecision(useShortNames, twoColumns, true);
    }
    useShortNames = true;
    if (fitsLayout(layoutInput, useShortNames, twoColumns)) {
      return new StrategyHudBase.LayoutDecision(useShortNames, twoColumns, true);
    }
    twoColumns = false;
    if (fitsLayout(layoutInput, useShortNames, twoColumns)) {
      return new StrategyHudBase.LayoutDecision(useShortNames, twoColumns, true);
    }
    return new StrategyHudBase.LayoutDecision(useShortNames, twoColumns, false);
  }

  private static boolean fitsLayout(
      StrategyHudBase.LayoutInput layoutInput, boolean useShortNames, boolean twoColumns) {
    int maxWidth = 0;
    if (layoutInput.error() != null && !layoutInput.error().isEmpty()) {
      maxWidth =
          Math.max(maxWidth, layoutInput.client().font.width("ERROR: " + layoutInput.error()));
    }

    if (!layoutInput.goals().isEmpty()) {
      int goalsSize = layoutInput.goals().size();
      if (!twoColumns || goalsSize < 8) {
        maxWidth =
            Math.max(
                maxWidth,
                StrategyHudBase.computeMaxWidth(layoutInput, layoutInput.goals(), useShortNames));
      } else {
        int leftCount = (goalsSize + 1) / 2;
        List<RideGoal> leftGoals = layoutInput.goals().subList(0, Math.min(leftCount, goalsSize));
        List<RideGoal> rightGoals =
            goalsSize > leftCount ? layoutInput.goals().subList(leftCount, goalsSize) : List.of();

        int leftMax = StrategyHudBase.computeMaxWidth(layoutInput, leftGoals, useShortNames);
        int rightMax = StrategyHudBase.computeMaxWidth(layoutInput, rightGoals, useShortNames);
        if (leftMax + rightMax + layoutInput.gap() > layoutInput.availableWidth()) {
          return false;
        }
        maxWidth = Math.max(maxWidth, leftMax);
        maxWidth = Math.max(maxWidth, rightMax);
      }
    }

    if (layoutInput.effectiveRide() != null
        && layoutInput.effectiveRide() != RideName.UNKNOWN
        && !layoutInput.currentRideInTop()) {
      RideGoal currentGoal = StrategyCalculator.getGoalForRide(layoutInput.effectiveRide());
      StrategyHudBase.FormattedRide fr =
          StrategyHudBase.formatRideName(
              layoutInput.effectiveRide(),
              layoutInput.currentRide(),
              layoutInput.autograbRide(),
              layoutInput.closestRide(),
              useShortNames,
              layoutInput.isPassenger());
      String text;
      if (currentGoal != null) {
        text =
            StrategyHudBase.formatGoalText(fr, currentGoal, ModConfig.currentSetting.sortingRules);
      } else if (fr.getStatus() == StrategyHudBase.RideStatus.RIDING) {
        text = "Riding: " + fr.getName();
      } else {
        text = fr.getName();
      }
      maxWidth = Math.max(maxWidth, layoutInput.client().font.width(text) / 2);
    }

    return maxWidth <= layoutInput.availableWidth();
  }
}
