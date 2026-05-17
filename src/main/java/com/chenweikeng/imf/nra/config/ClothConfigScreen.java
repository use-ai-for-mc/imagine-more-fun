package com.chenweikeng.imf.nra.config;

import com.chenweikeng.imf.nra.ServerState;
import com.chenweikeng.imf.nra.compat.MonkeycraftCompat;
import com.chenweikeng.imf.nra.ride.RideName;
import com.chenweikeng.imf.nra.util.TimeFormatUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.Requirement;
import me.shedaniel.clothconfig2.impl.builders.DropdownMenuBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

public class ClothConfigScreen {

  public static Object createScreen(net.minecraft.client.gui.screens.Screen parent) {
    return createScreen(parent, ModConfig.currentSetting, () -> ModConfig.save());
  }

  public static Object createScreen(
      net.minecraft.client.gui.screens.Screen parent, ConfigSetting profile, Runnable onSave) {
    ConfigBuilder builder =
        ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Component.translatable("config.not-riding-alert.title"))
            .setSavingRunnable(onSave);

    ConfigEntryBuilder entryBuilder = builder.entryBuilder();

    Minecraft client = Minecraft.getInstance();

    ConfigCategory general =
        builder.getOrCreateCategory(
            Component.translatable("config.not-riding-alert.category.general"));

    general.addEntry(
        entryBuilder
            .startBooleanToggle(
                Component.translatable("config.not-riding-alert.globalEnable"),
                profile.globalEnable)
            .setDefaultValue(ConfigDefaults.GLOBAL_ENABLE)
            .setTooltip(Component.translatable("config.not-riding-alert.globalEnable.tooltip"))
            .setSaveConsumer(newValue -> profile.globalEnable = newValue)
            .build());

    general.addEntry(
        entryBuilder
            .startBooleanToggle(
                Component.translatable("config.not-riding-alert.enabled"), profile.enabled)
            .setDefaultValue(ConfigDefaults.ENABLED)
            .setTooltip(Component.translatable("config.not-riding-alert.enabled.tooltip"))
            .setSaveConsumer(newValue -> profile.enabled = newValue)
            .build());

    general.addEntry(
        entryBuilder
            .startDropdownMenu(
                Component.translatable("config.not-riding-alert.soundId"),
                DropdownMenuBuilder.TopCellElementBuilder.of(profile.soundId, e -> e),
                DropdownMenuBuilder.CellCreatorBuilder.of())
            .setDefaultValue(ConfigDefaults.SOUND_ID)
            .setTooltip(Component.translatable("config.not-riding-alert.soundId.tooltip"))
            .setSelections(
                client.getSoundManager().getAvailableSounds().stream()
                    .map(identifier -> identifier.getPath())
                    .sorted()
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new)))
            .setSuggestionMode(true)
            .setSaveConsumer(soundId -> profile.soundId = soundId)
            .build());

    general.addEntry(
        entryBuilder
            .startEnumSelector(
                Component.translatable("config.not-riding-alert.cursorReleaseTiming"),
                CursorReleaseTiming.class,
                profile.cursorReleaseTiming)
            .setDefaultValue(CursorReleaseTiming.NONE)
            .setTooltip(
                Component.translatable("config.not-riding-alert.cursorReleaseTiming.tooltip"))
            .setSaveConsumer(newValue -> profile.cursorReleaseTiming = newValue)
            .setEnumNameProvider(
                timing ->
                    Component.translatable(
                        "config.not-riding-alert.cursorReleaseTiming."
                            + timing.name().toLowerCase()))
            .build());

    general.addEntry(
        entryBuilder
            .startBooleanToggle(
                Component.translatable("config.not-riding-alert.silent"), profile.silent)
            .setDefaultValue(ConfigDefaults.SILENT)
            .setTooltip(Component.translatable("config.not-riding-alert.silent.tooltip"))
            .setSaveConsumer(newValue -> profile.silent = newValue)
            .build());

    general.addEntry(
        entryBuilder
            .startEnumSelector(
                Component.translatable("config.not-riding-alert.minimizeWindow"),
                WindowMinimizeTiming.class,
                profile.minimizeWindow)
            .setDefaultValue(WindowMinimizeTiming.NONE)
            .setTooltip(Component.translatable("config.not-riding-alert.minimizeWindow.tooltip"))
            .setSaveConsumer(newValue -> profile.minimizeWindow = newValue)
            .setEnumNameProvider(
                timing ->
                    Component.translatable(
                        "config.not-riding-alert.minimizeWindow." + timing.name().toLowerCase()))
            .build());

    general.addEntry(
        entryBuilder
            .startBooleanToggle(
                Component.translatable("config.not-riding-alert.enableOpenAudioMc"),
                profile.enableOpenAudioMc)
            .setDefaultValue(ConfigDefaults.ENABLE_OPEN_AUDIO_MC)
            .setTooltip(Component.translatable("config.not-riding-alert.enableOpenAudioMc.tooltip"))
            .setSaveConsumer(newValue -> profile.enableOpenAudioMc = newValue)
            .build());

    general.addEntry(
        entryBuilder
            .startEnumSelector(
                Component.translatable("config.not-riding-alert.rideReportNotifyMode"),
                RideReportNotifyMode.class,
                profile.rideReportNotifyMode)
            .setDefaultValue(ConfigDefaults.RIDE_REPORT_NOTIFY_MODE)
            .setTooltip(
                Component.translatable("config.not-riding-alert.rideReportNotifyMode.tooltip"))
            .setSaveConsumer(newValue -> profile.rideReportNotifyMode = newValue)
            .setEnumNameProvider(
                mode ->
                    Component.translatable(
                        "config.not-riding-alert.rideReportNotifyMode."
                            + mode.name().toLowerCase()))
            .build());

    general.addEntry(
        entryBuilder
            .startBooleanToggle(
                Component.translatable("config.not-riding-alert.randomRideOverride"),
                profile.randomRideOverride)
            .setDefaultValue(ConfigDefaults.RANDOM_RIDE_OVERRIDE)
            .setTooltip(
                Component.translatable("config.not-riding-alert.randomRideOverride.tooltip"))
            .setSaveConsumer(newValue -> profile.randomRideOverride = newValue)
            .build());

    ConfigCategory visual =
        builder.getOrCreateCategory(
            Component.translatable("config.not-riding-alert.category.visual"));

    visual.addEntry(
        entryBuilder
            .startBooleanToggle(
                Component.translatable("config.not-riding-alert.blindWhenRiding"),
                profile.blindWhenRiding)
            .setDefaultValue(ConfigDefaults.BLIND_WHEN_RIDING)
            .setTooltip(Component.translatable("config.not-riding-alert.blindWhenRiding.tooltip"))
            .setSaveConsumer(newValue -> profile.blindWhenRiding = newValue)
            .build());

    visual.addEntry(
        entryBuilder
            .startEnumSelector(
                Component.translatable("config.not-riding-alert.fullbright"),
                FullbrightMode.class,
                profile.fullbrightMode)
            .setDefaultValue(FullbrightMode.NONE)
            .setTooltip(Component.translatable("config.not-riding-alert.fullbright.tooltip"))
            .setSaveConsumer(newValue -> profile.fullbrightMode = newValue)
            .setEnumNameProvider(
                mode ->
                    Component.translatable(
                        "config.not-riding-alert.fullbright." + mode.name().toLowerCase()))
            .build());

    visual.addEntry(
        entryBuilder
            .startBooleanToggle(
                Component.translatable("config.not-riding-alert.hideScoreboard"),
                profile.hideScoreboard)
            .setDefaultValue(ConfigDefaults.HIDE_SCOREBOARD)
            .setTooltip(Component.translatable("config.not-riding-alert.hideScoreboard.tooltip"))
            .setSaveConsumer(newValue -> profile.hideScoreboard = newValue)
            .build());

    visual.addEntry(
        entryBuilder
            .startBooleanToggle(
                Component.translatable("config.not-riding-alert.hideChat"), profile.hideChat)
            .setDefaultValue(ConfigDefaults.HIDE_CHAT)
            .setTooltip(Component.translatable("config.not-riding-alert.hideChat.tooltip"))
            .setSaveConsumer(newValue -> profile.hideChat = newValue)
            .build());

    visual.addEntry(
        entryBuilder
            .startBooleanToggle(
                Component.translatable("config.not-riding-alert.hideHealth"), profile.hideHealth)
            .setDefaultValue(ConfigDefaults.HIDE_HEALTH)
            .setTooltip(Component.translatable("config.not-riding-alert.hideHealth.tooltip"))
            .setSaveConsumer(newValue -> profile.hideHealth = newValue)
            .build());

    visual.addEntry(
        entryBuilder
            .startBooleanToggle(
                Component.translatable("config.not-riding-alert.hideNameTag"), profile.hideNameTag)
            .setDefaultValue(ConfigDefaults.HIDE_NAME_TAG)
            .setTooltip(Component.translatable("config.not-riding-alert.hideNameTag.tooltip"))
            .setSaveConsumer(newValue -> profile.hideNameTag = newValue)
            .build());

    visual.addEntry(
        entryBuilder
            .startBooleanToggle(
                Component.translatable("config.not-riding-alert.hideHotbar"), profile.hideHotbar)
            .setDefaultValue(ConfigDefaults.HIDE_HOTBAR)
            .setTooltip(Component.translatable("config.not-riding-alert.hideHotbar.tooltip"))
            .setSaveConsumer(newValue -> profile.hideHotbar = newValue)
            .build());

    visual.addEntry(
        entryBuilder
            .startBooleanToggle(
                Component.translatable("config.not-riding-alert.hideExperienceLevel"),
                profile.hideExperienceLevel)
            .setDefaultValue(ConfigDefaults.HIDE_EXPERIENCE_LEVEL)
            .setTooltip(
                Component.translatable("config.not-riding-alert.hideExperienceLevel.tooltip"))
            .setSaveConsumer(newValue -> profile.hideExperienceLevel = newValue)
            .build());

    visual.addEntry(
        entryBuilder
            .startBooleanToggle(
                Component.translatable("config.not-riding-alert.hideLovePotionMessages"),
                profile.hideLovePotionMessages)
            .setDefaultValue(ConfigDefaults.HIDE_LOVE_POTION_MESSAGES)
            .setTooltip(
                Component.translatable("config.not-riding-alert.hideLovePotionMessages.tooltip"))
            .setSaveConsumer(newValue -> profile.hideLovePotionMessages = newValue)
            .build());

    visual.addEntry(
        entryBuilder
            .startBooleanToggle(
                Component.translatable("config.not-riding-alert.showAutograbRegions"),
                profile.showAutograbRegions)
            .setDefaultValue(ConfigDefaults.SHOW_AUTOGRAB_REGIONS)
            .setTooltip(
                Component.translatable("config.not-riding-alert.showAutograbRegions.tooltip"))
            .setSaveConsumer(newValue -> profile.showAutograbRegions = newValue)
            .build());

    visual.addEntry(
        entryBuilder
            .startEnumSelector(
                Component.translatable("config.not-riding-alert.closedCaptionMode"),
                ClosedCaptionMode.class,
                profile.closedCaptionMode)
            .setDefaultValue(ConfigDefaults.CLOSED_CAPTION_MODE)
            .setTooltip(Component.translatable("config.not-riding-alert.closedCaptionMode.tooltip"))
            .setSaveConsumer(newValue -> profile.closedCaptionMode = newValue)
            .setEnumNameProvider(
                mode ->
                    Component.translatable(
                        "config.not-riding-alert.closedCaptionMode." + mode.name().toLowerCase()))
            .build());

    visual.addEntry(
        entryBuilder
            .startEnumSelector(
                Component.translatable("config.not-riding-alert.hideCrosshairMode"),
                HideCrosshairMode.class,
                profile.hideCrosshairMode)
            .setDefaultValue(ConfigDefaults.HIDE_CROSSHAIR_MODE)
            .setTooltip(Component.translatable("config.not-riding-alert.hideCrosshairMode.tooltip"))
            .setSaveConsumer(newValue -> profile.hideCrosshairMode = newValue)
            .setEnumNameProvider(
                mode ->
                    Component.translatable(
                        "config.not-riding-alert.hideCrosshairMode." + mode.name().toLowerCase()))
            .build());

    visual.addEntry(
        entryBuilder
            .startEnumSelector(
                Component.translatable("config.not-riding-alert.audioBoostReminderMode"),
                AudioBoostReminderMode.class,
                profile.audioBoostReminderMode)
            .setDefaultValue(ConfigDefaults.AUDIO_BOOST_REMINDER_MODE)
            .setTooltip(
                Component.translatable("config.not-riding-alert.audioBoostReminderMode.tooltip"))
            .setSaveConsumer(newValue -> profile.audioBoostReminderMode = newValue)
            .setEnumNameProvider(
                mode ->
                    Component.translatable(
                        "config.not-riding-alert.audioBoostReminderMode."
                            + mode.name().toLowerCase()))
            .build());

    visual.addEntry(
        entryBuilder
            .startBooleanToggle(
                Component.translatable("config.not-riding-alert.showSessionStats"),
                profile.showSessionStats)
            .setDefaultValue(ConfigDefaults.SHOW_SESSION_STATS)
            .setTooltip(Component.translatable("config.not-riding-alert.showSessionStats.tooltip"))
            .setSaveConsumer(newValue -> profile.showSessionStats = newValue)
            .build());

    ConfigCategory modifications =
        builder.getOrCreateCategory(
            Component.translatable("config.not-riding-alert.category.modifications"));

    // "Space Mountain" group — a collapsible sub-category. Each ride's modification toggles get
    // their own group in this tab; add more groups the same way as they're built.
    List<AbstractConfigListEntry> spaceMountainGroup = new ArrayList<>();
    spaceMountainGroup.add(
        entryBuilder
            .startBooleanToggle(
                Component.translatable("config.not-riding-alert.spaceMountainEnhancements"),
                profile.spaceMountainEnhancements)
            .setDefaultValue(ConfigDefaults.SPACE_MOUNTAIN_ENHANCEMENTS)
            .setTooltip(
                Component.translatable("config.not-riding-alert.spaceMountainEnhancements.tooltip"))
            .setSaveConsumer(newValue -> profile.spaceMountainEnhancements = newValue)
            .build());
    modifications.addEntry(
        entryBuilder
            .startSubCategory(
                Component.translatable("config.not-riding-alert.spaceMountainGroup"),
                spaceMountainGroup)
            .setExpanded(true)
            .build());

    ConfigCategory tracker =
        builder.getOrCreateCategory(
            Component.translatable("config.not-riding-alert.category.rides"));

    var rideHubEntry =
        entryBuilder
            .startEnumSelector(
                Component.translatable("config.not-riding-alert.rideHubMode"),
                RideHubMode.class,
                profile.rideHubMode)
            .setDefaultValue(ConfigDefaults.RIDE_HUB_MODE)
            .setTooltip(Component.translatable("config.not-riding-alert.rideHubMode.tooltip"))
            .setSaveConsumer(newValue -> profile.rideHubMode = newValue)
            .setEnumNameProvider(
                mode ->
                    Component.translatable(
                        "config.not-riding-alert.rideHubMode." + mode.name().toLowerCase()))
            .build();
    tracker.addEntry(rideHubEntry);

    Requirement strategyHubOnly = Requirement.isValue(rideHubEntry, RideHubMode.STRATEGY_HUB);

    tracker.addEntry(
        entryBuilder
            .startEnumSelector(
                Component.translatable("config.not-riding-alert.trackerDisplayMode"),
                TrackerDisplayMode.class,
                profile.trackerDisplayMode)
            .setDefaultValue(ConfigDefaults.TRACKER_DISPLAY_MODE)
            .setTooltip(
                Component.translatable("config.not-riding-alert.trackerDisplayMode.tooltip"))
            .setSaveConsumer(newValue -> profile.trackerDisplayMode = newValue)
            .setEnumNameProvider(
                mode ->
                    Component.translatable(
                        "config.not-riding-alert.trackerDisplayMode." + mode.name().toLowerCase()))
            .build());

    tracker.addEntry(
        entryBuilder
            .startEnumSelector(
                Component.translatable("config.not-riding-alert.strategyHudRendererVersion"),
                StrategyHudRendererVersion.class,
                profile.strategyHudRendererVersion)
            .setDefaultValue(ConfigDefaults.STRATEGY_HUD_RENDERER_VERSION)
            .setTooltip(
                Component.translatable(
                    "config.not-riding-alert.strategyHudRendererVersion.tooltip"))
            .setSaveConsumer(newValue -> profile.strategyHudRendererVersion = newValue)
            .setEnumNameProvider(
                version ->
                    Component.translatable(
                        "config.not-riding-alert.strategyHudRendererVersion."
                            + version.name().toLowerCase()))
            .setDisplayRequirement(strategyHubOnly)
            .build());

    tracker.addEntry(
        entryBuilder
            .startIntSlider(
                Component.translatable("config.not-riding-alert.rideDisplayCount"),
                profile.rideDisplayCount,
                0,
                60)
            .setDefaultValue(ConfigDefaults.RIDE_DISPLAY_COUNT)
            .setTooltip(Component.translatable("config.not-riding-alert.rideDisplayCount.tooltip"))
            .setSaveConsumer(newValue -> profile.rideDisplayCount = newValue)
            .setDisplayRequirement(strategyHubOnly)
            .build());

    tracker.addEntry(
        entryBuilder
            .startEnumSelector(
                Component.translatable("config.not-riding-alert.sortingRules"),
                SortingRules.class,
                profile.sortingRules)
            .setDefaultValue(ConfigDefaults.SORTING_RULES)
            .setTooltip(Component.translatable("config.not-riding-alert.sortingRules.tooltip"))
            .setSaveConsumer(newValue -> profile.sortingRules = newValue)
            .setEnumNameProvider(
                rule ->
                    Component.translatable(
                        "config.not-riding-alert.sortingRules." + rule.name().toLowerCase()))
            .setDisplayRequirement(strategyHubOnly)
            .build());

    tracker.addEntry(
        entryBuilder
            .startIntSlider(
                Component.translatable("config.not-riding-alert.minRideTimeMinutes"),
                profile.minRideTimeMinutes == null ? 0 : profile.minRideTimeMinutes,
                0,
                16)
            .setDefaultValue(ConfigDefaults.MIN_RIDE_TIME_MINUTES)
            .setTooltip(
                Component.translatable("config.not-riding-alert.minRideTimeMinutes.tooltip"))
            .setSaveConsumer(
                newValue -> {
                  if (newValue <= 0) {
                    profile.minRideTimeMinutes = 0;
                  } else {
                    profile.minRideTimeMinutes = newValue;
                  }
                })
            .setDisplayRequirement(strategyHubOnly)
            .build());

    tracker.addEntry(
        entryBuilder
            .startEnumSelector(
                Component.translatable("config.not-riding-alert.maxGoal"),
                MaxGoal.class,
                profile.maxGoal)
            .setDefaultValue(ConfigDefaults.MAX_GOAL)
            .setTooltip(Component.translatable("config.not-riding-alert.maxGoal.tooltip"))
            .setSaveConsumer(newValue -> profile.maxGoal = newValue)
            .setEnumNameProvider(
                goal ->
                    Component.translatable(
                        "config.not-riding-alert.maxGoal." + goal.name().toLowerCase()))
            .build());

    tracker.addEntry(
        entryBuilder
            .startIntSlider(
                Component.translatable("config.not-riding-alert.hudBackgroundOpacity"),
                profile.hudBackgroundOpacity,
                0,
                100)
            .setDefaultValue(ConfigDefaults.HUD_BACKGROUND_OPACITY)
            .setTooltip(
                Component.translatable("config.not-riding-alert.hudBackgroundOpacity.tooltip"))
            .setSaveConsumer(newValue -> profile.hudBackgroundOpacity = newValue)
            .build());

    tracker.addEntry(
        entryBuilder
            .startBooleanToggle(
                Component.translatable("config.not-riding-alert.onlyAutograbbing"),
                profile.onlyAutograbbing)
            .setDefaultValue(ConfigDefaults.ONLY_AUTOGRABBING)
            .setTooltip(Component.translatable("config.not-riding-alert.onlyAutograbbing.tooltip"))
            .setSaveConsumer(newValue -> profile.onlyAutograbbing = newValue)
            .build());

    tracker.addEntry(
        entryBuilder
            .startColorField(
                Component.translatable("config.not-riding-alert.trackerNormalColor"),
                TextColor.fromRgb(profile.trackerNormalColor & 0x00FFFFFF))
            .setDefaultValue(TextColor.fromRgb(ConfigDefaults.TRACKER_NORMAL_COLOR & 0x00FFFFFF))
            .setTooltip(
                Component.translatable("config.not-riding-alert.trackerNormalColor.tooltip"))
            .setSaveConsumer2(color -> profile.trackerNormalColor = color.getColor() | 0xFF000000)
            .setDisplayRequirement(strategyHubOnly)
            .build());

    tracker.addEntry(
        entryBuilder
            .startColorField(
                Component.translatable("config.not-riding-alert.trackerAutograbbingColor"),
                TextColor.fromRgb(profile.trackerAutograbbingColor & 0x00FFFFFF))
            .setDefaultValue(
                TextColor.fromRgb(ConfigDefaults.TRACKER_AUTOGRABBING_COLOR & 0x00FFFFFF))
            .setTooltip(
                Component.translatable("config.not-riding-alert.trackerAutograbbingColor.tooltip"))
            .setSaveConsumer2(
                color -> profile.trackerAutograbbingColor = color.getColor() | 0xFF000000)
            .build());

    tracker.addEntry(
        entryBuilder
            .startColorField(
                Component.translatable("config.not-riding-alert.trackerRidingColor"),
                TextColor.fromRgb(profile.trackerRidingColor & 0x00FFFFFF))
            .setDefaultValue(TextColor.fromRgb(ConfigDefaults.TRACKER_RIDING_COLOR & 0x00FFFFFF))
            .setTooltip(
                Component.translatable("config.not-riding-alert.trackerRidingColor.tooltip"))
            .setSaveConsumer2(color -> profile.trackerRidingColor = color.getColor() | 0xFF000000)
            .build());

    tracker.addEntry(
        entryBuilder
            .startColorField(
                Component.translatable("config.not-riding-alert.trackerErrorColor"),
                TextColor.fromRgb(profile.trackerErrorColor & 0x00FFFFFF))
            .setDefaultValue(TextColor.fromRgb(ConfigDefaults.TRACKER_ERROR_COLOR & 0x00FFFFFF))
            .setTooltip(Component.translatable("config.not-riding-alert.trackerErrorColor.tooltip"))
            .setSaveConsumer(color -> profile.trackerErrorColor = color | 0xFF000000)
            .setDisplayRequirement(strategyHubOnly)
            .build());

    tracker.addEntry(
        entryBuilder
            .startEnumSelector(
                Component.translatable("config.not-riding-alert.closestRideMode"),
                ClosestRideMode.class,
                profile.closestRideMode)
            .setDefaultValue(ConfigDefaults.CLOSEST_RIDE_MODE)
            .setTooltip(Component.translatable("config.not-riding-alert.closestRideMode.tooltip"))
            .setSaveConsumer(newValue -> profile.closestRideMode = newValue)
            .setEnumNameProvider(
                mode ->
                    Component.translatable(
                        "config.not-riding-alert.closestRideMode." + mode.name().toLowerCase()))
            .setDisplayRequirement(strategyHubOnly)
            .build());

    tracker.addEntry(
        entryBuilder
            .startColorField(
                Component.translatable("config.not-riding-alert.trackerClosestRideColor"),
                TextColor.fromRgb(profile.trackerClosestRideColor & 0x00FFFFFF))
            .setDefaultValue(
                TextColor.fromRgb(ConfigDefaults.TRACKER_CLOSEST_RIDE_COLOR & 0x00FFFFFF))
            .setTooltip(
                Component.translatable("config.not-riding-alert.trackerClosestRideColor.tooltip"))
            .setSaveConsumer2(
                color -> profile.trackerClosestRideColor = color.getColor() | 0xFF000000)
            .setDisplayRequirement(strategyHubOnly)
            .build());

    ConfigCategory advanceNotice =
        builder.getOrCreateCategory(
            Component.translatable("config.not-riding-alert.category.advanceNotice"));

    for (RideName ride : RideName.sortedByDisplayName()) {
      int currentValue = profile.advanceNoticeSeconds.getOrDefault(ride.toMatchString(), 0);
      advanceNotice.addEntry(
          entryBuilder
              .startIntSlider(formatRideLabel(ride), currentValue, 0, 30)
              .setDefaultValue(0)
              .setTooltip(Component.translatable("config.not-riding-alert.advanceNotice.tooltip"))
              .setSaveConsumer(
                  newValue -> {
                    if (newValue > 0) {
                      profile.advanceNoticeSeconds.put(ride.toMatchString(), newValue);
                    } else {
                      profile.advanceNoticeSeconds.remove(ride.toMatchString());
                    }
                  })
              .build());
    }

    ConfigCategory rides =
        builder.getOrCreateCategory(
            Component.translatable("config.not-riding-alert.category.rideDisplay"));

    for (RideName ride : RideName.sortedByDisplayName()) {
      boolean currentValue = !profile.hiddenRides.contains(ride.toMatchString());
      rides.addEntry(
          entryBuilder
              .startBooleanToggle(formatRideLabel(ride), currentValue)
              .setDefaultValue(!ride.isSeasonal())
              .setTooltip(Component.translatable("config.not-riding-alert.rideDisplay.tooltip"))
              .setSaveConsumer(
                  newValue -> {
                    if (!newValue) {
                      profile.hiddenRides.add(ride.toMatchString());
                    } else {
                      profile.hiddenRides.remove(ride.toMatchString());
                    }
                  })
              .build());
    }

    if (ServerState.isImagineFunServer() && MonkeycraftCompat.isAvailable()) {
      ConfigCategory monkeyCraft = builder.getOrCreateCategory(Component.literal("MonkeyCraft"));

      monkeyCraft.addEntry(
          entryBuilder
              .startBooleanToggle(
                  Component.literal("Hibernating when riding"), profile.hibernationWhenRiding)
              .setDefaultValue(ConfigDefaults.HIBERNATION_WHEN_RIDING)
              .setTooltip(
                  Component.literal(
                      "When enabled, the mod will start hibernation when riding. When disabled, new hibernations won't start, but existing ones can still be updated or ended."))
              .setSaveConsumer(newValue -> profile.hibernationWhenRiding = newValue)
              .build());
    }

    return builder.build();
  }

  private static Component formatRideLabel(RideName ride) {
    String timeString = TimeFormatUtil.formatDuration(ride.getRideTime());
    return Component.literal(String.format("%s (Time: %s)", ride.getDisplayName(), timeString));
  }
}
