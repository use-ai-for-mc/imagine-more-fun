package com.chenweikeng.imf.nra.strategy;

import com.chenweikeng.imf.mixin.NraBossHealthOverlayAccessor;
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
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;

/**
 * Strategy HUD v1 — full-width top bar with semi-transparent background.
 *
 * <p>Differs from v0 in three ways: (1) renders at y=0 with a full-width background bar, (2) always
 * uses two columns, and (3) hides when a vanilla boss bar is visible.
 *
 * <p>Shared formatting and helpers live in {@link StrategyHudBase}.
 */
public class StrategyHudRendererV1 {
  private static List<RideGoal> topGoals = new ArrayList<>();
  private static int updateCounter = 0;
  private static final int UPDATE_INTERVAL_TICKS = Timing.HUD_UPDATE_INTERVAL_TICKS;
  private static String currentError = null;

  // ---- public API ----

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

    // Suppress when a vanilla boss bar is visible.
    BossHealthOverlay bossOverlay = client.gui.getBossOverlay();
    Map<UUID, LerpingBossEvent> bossEvents =
        ((NraBossHealthOverlayAccessor) bossOverlay).getEvents();
    if (bossEvents != null && !bossEvents.isEmpty()) {
      return;
    }

    update();

    int screenWidth = client.getWindow().getGuiScaledWidth();
    int textPadding = 5;
    int xLeft = textPadding;
    int xRight = screenWidth - textPadding;
    int yStart = 0;
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
    int halfWidth = screenWidth / 2 - textPadding * 2;
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
            halfWidth,
            0);

    StrategyHudBase.LayoutDecision decision =
        decideLayout(layoutInput, ModConfig.currentSetting.displayShortName);
    if (!decision.visible()) {
      return;
    }
    boolean useShortNames = decision.useShortNames();

    List<RideGoal> leftGoals;
    List<RideGoal> rightGoals;
    if (displayCount > 0) {
      int leftCount = (topGoalsSize + 1) / 2;
      leftGoals = topGoals.subList(0, Math.min(leftCount, topGoalsSize));
      rightGoals = topGoalsSize > leftCount ? topGoals.subList(leftCount, topGoalsSize) : List.of();
    } else {
      leftGoals = List.of();
      rightGoals = List.of();
    }

    int maxColumnHeight = Math.max(leftGoals.size(), rightGoals.size());

    boolean hasError = currentError != null && !currentError.isEmpty();
    boolean hasExtraRide =
        effectiveRide != null && effectiveRide != RideName.UNKNOWN && !currentRideInTop;

    int totalLines = (hasError ? 1 : 0) + maxColumnHeight + (hasExtraRide ? 2 : 0);
    int bgHeight = totalLines * lineHeight;
    int bgY1 = yStart;
    int bgY2 = yStart + bgHeight + 4;

    int opacity = ModConfig.currentSetting.hudBackgroundOpacity;
    if (opacity > 0) {
      int alpha = (int) (opacity * 2.55);
      int bgColor = (alpha << 24);
      context.fill(0, bgY1, screenWidth, bgY2, bgColor);
    }

    int y = yStart + 2;

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

    if (hasExtraRide) {
      int extraY = yStart + 2 + ((hasError ? 1 : 0) + maxColumnHeight + 1) * lineHeight;
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

  // ---- V1-specific helpers ----

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
      drawGoal(
          context,
          client,
          goals.get(i),
          currentRide,
          autograbRide,
          closestRide,
          useShortNames,
          isPassenger,
          colorNormal,
          colorRiding,
          colorAutograbbing,
          colorClosest,
          x,
          y + (i * lineHeight),
          false);
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
      drawGoal(
          context,
          client,
          goals.get(i),
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
          y + (i * lineHeight),
          true);
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

  private static void drawGoal(
      GuiGraphics context,
      Minecraft client,
      RideGoal goal,
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
      boolean alignRight) {
    StrategyHudBase.FormattedRide fr =
        StrategyHudBase.formatRideName(
            goal.getRide(), currentRide, autograbRide, closestRide, useShortNames, isPassenger);
    String text = StrategyHudBase.formatGoalText(fr, goal, ModConfig.currentSetting.sortingRules);
    int color =
        StrategyHudBase.getColorForStatus(
            fr.getStatus(), colorNormal, colorRiding, colorAutograbbing, colorClosest);
    int drawX = alignRight ? x - client.font.width(text) : x;
    context.drawString(client.font, text, drawX, y, color, false);
  }

  // ---- V1-specific layout (always two columns) ----

  private static StrategyHudBase.LayoutDecision decideLayout(
      StrategyHudBase.LayoutInput layoutInput, boolean baseUseShortNames) {
    boolean useShortNames = baseUseShortNames;
    if (fitsLayout(layoutInput, useShortNames)) {
      return new StrategyHudBase.LayoutDecision(useShortNames, true, true);
    }
    useShortNames = true;
    if (fitsLayout(layoutInput, useShortNames)) {
      return new StrategyHudBase.LayoutDecision(useShortNames, true, true);
    }
    return new StrategyHudBase.LayoutDecision(useShortNames, true, false);
  }

  private static boolean fitsLayout(
      StrategyHudBase.LayoutInput layoutInput, boolean useShortNames) {
    int maxWidth = 0;
    if (layoutInput.error() != null && !layoutInput.error().isEmpty()) {
      maxWidth =
          Math.max(maxWidth, layoutInput.client().font.width("ERROR: " + layoutInput.error()));
    }

    if (!layoutInput.goals().isEmpty()) {
      int goalsSize = layoutInput.goals().size();
      int leftCount = (goalsSize + 1) / 2;
      List<RideGoal> leftGoals = layoutInput.goals().subList(0, Math.min(leftCount, goalsSize));
      List<RideGoal> rightGoals =
          goalsSize > leftCount ? layoutInput.goals().subList(leftCount, goalsSize) : List.of();

      int leftMax = StrategyHudBase.computeMaxWidth(layoutInput, leftGoals, useShortNames);
      int rightMax = StrategyHudBase.computeMaxWidth(layoutInput, rightGoals, useShortNames);
      maxWidth = Math.max(maxWidth, leftMax);
      maxWidth = Math.max(maxWidth, rightMax);
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
