package com.chenweikeng.imf.nra.wizard.pages;

import com.chenweikeng.imf.nra.config.MaxGoal;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.config.SortingRules;
import com.chenweikeng.imf.nra.config.StrategyHudRendererVersion;
import com.chenweikeng.imf.nra.config.TrackerDisplayMode;
import com.chenweikeng.imf.nra.strategy.RideGoal;
import com.chenweikeng.imf.nra.strategy.StrategyCalculator;
import com.chenweikeng.imf.nra.strategy.StrategyHudBase;
import com.chenweikeng.imf.nra.wizard.WizardPage;
import com.chenweikeng.imf.nra.wizard.layout.RenderBlock;
import com.chenweikeng.imf.nra.wizard.layout.VerticalAlignment;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class Page6TrackerSettings extends WizardPage {

  public Page6TrackerSettings() {
    super(5);
  }

  @Override
  public Component getTitle() {
    return literal("Tracker Settings");
  }

  @Override
  public List<RenderBlock> getBlocks(Minecraft client) {
    List<RenderBlock> blocks = new ArrayList<>();

    blocks.add(text(trackerDisplayModeSection()));

    boolean isTrackerVisible =
        ModConfig.currentSetting.trackerDisplayMode != TrackerDisplayMode.NEVER;
    if (isTrackerVisible) {
      blocks.add(separator(20));
      blocks.addAll(strategyRendererSectionBlocks());
      blocks.add(separator(20));

      List<RenderBlock> leftColumn = new ArrayList<>();
      leftColumn.add(text(onlyAutograbbingSection()));
      leftColumn.add(separator(20));
      leftColumn.add(text(rideDisplayCountSection()));
      leftColumn.add(separator(20));
      leftColumn.add(text(strategyDurationSection()));
      leftColumn.add(separator(20));
      leftColumn.add(text(maxGoalSection()));
      leftColumn.add(separator(20));
      leftColumn.add(text(minRideTimeSection()));

      blocks.add(
          row(
              column(leftColumn.toArray(new RenderBlock[0])),
              row(text(sortingRulesSection()), exampleGoalsSection())));
    }

    return blocks;
  }

  private Component trackerDisplayModeSection() {
    TrackerDisplayMode current = ModConfig.currentSetting.trackerDisplayMode;

    Component content = literal("");
    content = append(content, bold("Tracker Display: "));
    content = append(content, trackerModeLink("Always", TrackerDisplayMode.ALWAYS, current));
    content = append(content, literal(" | "));
    content =
        append(
            content,
            trackerModeLink("Only when riding", TrackerDisplayMode.ONLY_WHEN_RIDING, current));
    content = append(content, literal(" | "));
    content =
        append(
            content,
            trackerModeLink(
                "Only when not riding", TrackerDisplayMode.ONLY_WHEN_NOT_RIDING, current));
    content = append(content, literal(" | "));
    content = append(content, trackerModeLink("Never", TrackerDisplayMode.NEVER, current));
    return content;
  }

  private Component trackerModeLink(
      String label, TrackerDisplayMode mode, TrackerDisplayMode current) {
    if (mode == current) {
      return colored(label, ChatFormatting.GREEN);
    }
    return link(label, "config:trackerDisplayMode:" + mode.name());
  }

  private Component onlyAutograbbingSection() {
    boolean isOnlyAutograbbing = ModConfig.currentSetting.onlyAutograbbing;
    Component status =
        isOnlyAutograbbing
            ? colored("Enabled", ChatFormatting.GREEN)
            : colored("Disabled", ChatFormatting.RED);

    Component content = literal("");
    content = append(content, bold("Only Autograbbing Rides"));
    content = append(content, literal("\n\n"));
    content = append(content, literal("Filter tracker to autograbbing rides only? "));
    content = append(content, status);
    content = append(content, literal("  "));
    content = append(content, isEnabledLink(isOnlyAutograbbing, "onlyAutograbbing"));

    return content;
  }

  private Component rideDisplayCountSection() {
    int current = ModConfig.currentSetting.rideDisplayCount;

    Component content = literal("");
    content = append(content, bold("Ride Display Count\n\n"));
    Component showText =
        Component.literal("")
            .append(literal("Show"))
            .append(colored(" " + current + " ", ChatFormatting.GREEN))
            .append(literal("rides in tracker"));
    content = append(content, showText);

    Component decText =
        Component.literal("")
            .append(literal("  "))
            .append(decrementLink(rideDisplayCountLink(current - 1), "[-]"))
            .append(literal("  "))
            .append(incrementLink(rideDisplayCountLink(current + 1), "[+]"));
    content = append(content, decText);

    return content;
  }

  private Component maxGoalSection() {
    MaxGoal current = ModConfig.currentSetting.maxGoal;

    Component content = literal("");
    content = append(content, bold("Maximum Goal\n\n"));
    content = append(content, literal("Track goals up to "));
    content = append(content, colored(current.getDisplayName() + " ", ChatFormatting.GREEN));
    content = append(content, colored("(", ChatFormatting.GREEN));
    content = append(content, colored(current.getDisplayValue(), ChatFormatting.GREEN));
    content = append(content, colored(")", ChatFormatting.GREEN));
    content = append(content, literal("  "));
    content = append(content, decrementLink(maxGoalLink(current.previous()), "[-]"));
    content = append(content, literal("  "));
    content = append(content, incrementLink(maxGoalLink(current.next()), "[+]"));

    return content;
  }

  private Component strategyDurationSection() {
    boolean useTotalHours = ModConfig.currentSetting.strategyHubUseTotalHours;
    Component status =
        useTotalHours
            ? colored("Enabled", ChatFormatting.GREEN)
            : colored("Disabled", ChatFormatting.RED);

    Component content = literal("");
    content = append(content, bold("Long Duration Format"));
    content = append(content, literal("\n\n"));
    content = append(content, literal("Show durations over 24 hours as total hours and minutes? "));
    content = append(content, status);
    content = append(content, literal("  "));
    content = append(content, isEnabledLink(useTotalHours, "strategyHubUseTotalHours"));
    return content;
  }

  private Component minRideTimeSection() {
    Integer current =
        ModConfig.currentSetting.minRideTimeMinutes == null
            ? 0
            : ModConfig.currentSetting.minRideTimeMinutes;
    String displayValue = current == 0 ? "No filter" : current + " min";

    Component content = literal("");
    content = append(content, bold("Minimum Ride Time\n\n"));
    content = append(content, literal("Filter out rides shorter than "));
    content = append(content, colored(displayValue + " ", ChatFormatting.GREEN));
    content = append(content, literal("(0-16)"));
    content = append(content, literal("  "));

    int min = Math.max(0, current - 1);
    int max = Math.min(16, current + 1);
    Component decLink = decrementLink(minRideTimeLink(min), "[-]");
    Component incLink = incrementLink(minRideTimeLink(max), "[+]");

    content = append(content, decLink);
    content = append(content, literal("  "));
    content = append(content, incLink);

    return content;
  }

  private String rideDisplayCountLink(int value) {
    return "config:rideDisplayCount:" + value;
  }

  private String maxGoalLink(MaxGoal goal) {
    return "config:maxGoal:" + goal.name();
  }

  private String minRideTimeLink(Integer value) {
    return "config:minRideTimeMinutes:" + value;
  }

  private Component decrementLink(String linkTarget, String label) {
    return link(label, linkTarget);
  }

  private Component incrementLink(String linkTarget, String label) {
    return link(label, linkTarget);
  }

  private Component sortingRulesLinks() {
    SortingRules current = ModConfig.currentSetting.sortingRules;

    Component content = Component.literal("");

    // Max Goal sorting
    content = append(content, literal("Sort by Max Goal:"));
    content = append(content, literal("\n"));
    content = append(content, literal("  Time to reaching the maximum goal ("));
    content = append(content, literal(ModConfig.currentSetting.maxGoal.getDisplayName()));
    content = append(content, literal(")"));
    content = append(content, literal("\n"));
    Component maxGoalAsc =
        sortingOptionLink("Shorter first", SortingRules.TOTAL_TIME_ASC, current, "sortingRules");
    Component maxGoalDesc =
        sortingOptionLink("Longer first", SortingRules.TOTAL_TIME_DESC, current, "sortingRules");
    content = append(content, literal("  "));
    content = append(content, maxGoalAsc);
    content = append(content, literal(" / "));
    content = append(content, maxGoalDesc);
    content = append(content, literal("\n\n"));

    // Ride Time sorting
    content = append(content, colored("Sort by Ride Time:", ChatFormatting.WHITE));
    content = append(content, literal("\n"));
    content = append(content, literal("  A single ride duration"));
    content = append(content, literal("\n"));
    Component rideTimeAsc =
        sortingOptionLink("Shorter first", SortingRules.RIDE_TIME_ASC, current, "sortingRules");
    Component rideTimeDesc =
        sortingOptionLink("Longer first", SortingRules.RIDE_TIME_DESC, current, "sortingRules");
    content = append(content, literal("  "));
    content = append(content, rideTimeAsc);
    content = append(content, literal(" / "));
    content = append(content, rideTimeDesc);
    content = append(content, literal("\n\n"));

    // Next Goal sorting
    content = append(content, colored("Sort by Next Goal:", ChatFormatting.WHITE));
    content = append(content, literal("\n"));
    content = append(content, literal("  Time to reach to next goal"));
    content = append(content, literal("\n"));
    Component nextGoalAsc =
        sortingOptionLink("Shorter first", SortingRules.NEXT_GOAL_ASC, current, "sortingRules");
    Component nextGoalDesc =
        sortingOptionLink("Longer first", SortingRules.NEXT_GOAL_DESC, current, "sortingRules");
    content = append(content, literal("  "));
    content = append(content, nextGoalAsc);
    content = append(content, literal(" / "));
    content = append(content, nextGoalDesc);
    content = append(content, literal("\n\n"));

    return content;
  }

  private Component sortingRulesSection() {
    Component content = literal("");
    content = append(content, bold("Sorting Rules"));
    content = append(content, literal("\n\n"));
    content = append(content, sortingRulesLinks());

    return content;
  }

  private RenderBlock exampleGoalsSection() {
    List<RideGoal> topGoals = StrategyCalculator.getTopGoals(10);
    Component content = literal("\n\n");

    for (RideGoal goal : topGoals) {
      String name = goal.getRide().getShortName().toUpperCase();
      String ridesNeeded;
      String timeNeeded;

      if (ModConfig.currentSetting.sortingRules == SortingRules.TOTAL_TIME_ASC
          || ModConfig.currentSetting.sortingRules == SortingRules.TOTAL_TIME_DESC) {
        ridesNeeded = goal.getMaxRidesNeeded() + "+";
        timeNeeded = StrategyHudBase.formatStrategyDuration(goal.getMaxTimeNeeded());
      } else {
        ridesNeeded = goal.getNextGoalRidesNeeded() + "+";
        timeNeeded = StrategyHudBase.formatStrategyDuration(goal.getNextGoalTimeNeeded());
      }

      content = append(content, literal(name + ": "));
      content = append(content, literal(ridesNeeded + ", "));
      content = append(content, literal(timeNeeded));
      content = append(content, literal("\n"));
    }

    return text(content);
  }

  private Component rendererLink(
      String label, StrategyHudRendererVersion version, StrategyHudRendererVersion current) {
    if (version == current) {
      return colored(label, ChatFormatting.GREEN);
    }
    return link(label, "config:strategyHudRendererVersion:" + version.name());
  }

  private <T extends Enum<T>> Component sortingOptionLink(
      String label, T mode, T current, String configKey) {
    if (mode == current) {
      return colored(label, ChatFormatting.GREEN);
    }
    return link(label, "config:" + configKey + ":" + mode.name());
  }

  private Component append(Component base, Component toAppend) {
    return base.copy().append(toAppend);
  }

  private Component isEnabledLink(boolean current, String configKey) {
    boolean newValue = !current;
    return link(newValue ? "Enable" : "Disable", "config:" + configKey + ":" + newValue);
  }

  private List<RenderBlock> strategyRendererSectionBlocks() {
    List<RenderBlock> blocks = new ArrayList<>();
    StrategyHudRendererVersion current = ModConfig.currentSetting.strategyHudRendererVersion;

    Identifier img0 = Identifier.fromNamespaceAndPath("not-riding-alert", "textures/v0.png");
    Identifier img1 = Identifier.fromNamespaceAndPath("not-riding-alert", "textures/v1.png");
    Identifier img2 = Identifier.fromNamespaceAndPath("not-riding-alert", "textures/v2.png");

    Component textV0 = Component.empty();
    textV0 = append(textV0, rendererLink("V0", StrategyHudRendererVersion.V0, current));
    textV0 = append(textV0, literal(" Upper center with full details"));

    List<RenderBlock> v0Column = new ArrayList<>();
    v0Column.add(text(textV0));
    v0Column.add(image(img0, 273, 54));

    Component textV1 = Component.empty();
    textV1 = append(textV1, rendererLink("V1", StrategyHudRendererVersion.V1, current));
    textV1 = append(textV1, literal(" Top ceiling with full details"));

    List<RenderBlock> v1Column = new ArrayList<>();
    v1Column.add(text(textV1));
    v1Column.add(image(img1, 273, 54));

    Component textV2 = Component.empty();
    textV2 = append(textV2, rendererLink("V2", StrategyHudRendererVersion.V2, current));
    textV2 = append(textV2, literal(" Top ceiling with compact layout"));

    List<RenderBlock> v2Column = new ArrayList<>();
    v2Column.add(text(textV2));
    v2Column.add(image(img2, 273, 54));

    blocks.add(
        row(
            VerticalAlignment.TOP,
            column(v0Column.toArray(new RenderBlock[0])),
            column(v1Column.toArray(new RenderBlock[0])),
            column(v2Column.toArray(new RenderBlock[0]))));

    return blocks;
  }
}
