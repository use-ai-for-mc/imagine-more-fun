package com.chenweikeng.imf.nra.config;

import com.chenweikeng.imf.nra.ride.RideName;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class ConfigSetting {
  public boolean globalEnable = ConfigDefaults.GLOBAL_ENABLE;
  public boolean enabled = ConfigDefaults.ENABLED;
  public String soundId = ConfigDefaults.SOUND_ID;
  public boolean blindWhenRiding = ConfigDefaults.BLIND_WHEN_RIDING;
  public FullbrightMode fullbrightMode = ConfigDefaults.FULLBRIGHT_MODE;
  public CursorReleaseTiming cursorReleaseTiming = ConfigDefaults.CURSOR_RELEASE_TIMING;
  public boolean silent = ConfigDefaults.SILENT;
  public Integer minRideTimeMinutes = ConfigDefaults.MIN_RIDE_TIME_MINUTES;
  public int rideDisplayCount = ConfigDefaults.RIDE_DISPLAY_COUNT;
  public HashSet<String> hiddenRides =
      new HashSet<>(
          Arrays.stream(RideName.values())
              .filter(RideName::isSeasonal)
              .map(RideName::toMatchString)
              .collect(Collectors.toSet()));
  public boolean hideScoreboard = ConfigDefaults.HIDE_SCOREBOARD;
  public boolean hideChat = ConfigDefaults.HIDE_CHAT;
  public boolean hideHealth = ConfigDefaults.HIDE_HEALTH;
  public boolean hideNameTag = ConfigDefaults.HIDE_NAME_TAG;
  public boolean hideHotbar = ConfigDefaults.HIDE_HOTBAR;
  public boolean hideExperienceLevel = ConfigDefaults.HIDE_EXPERIENCE_LEVEL;
  public boolean onlyAutograbbing = ConfigDefaults.ONLY_AUTOGRABBING;
  public boolean hideLovePotionMessages = ConfigDefaults.HIDE_LOVE_POTION_MESSAGES;
  public boolean displayShortName = ConfigDefaults.DISPLAY_SHORT_NAME;
  public boolean showAutograbRegions = ConfigDefaults.SHOW_AUTOGRAB_REGIONS;
  public int hudBackgroundOpacity = ConfigDefaults.HUD_BACKGROUND_OPACITY;
  public WindowMinimizeTiming minimizeWindow = ConfigDefaults.MINIMIZE_WINDOW;
  public boolean hibernationWhenRiding = ConfigDefaults.HIBERNATION_WHEN_RIDING;
  public StrategyHudRendererVersion strategyHudRendererVersion =
      ConfigDefaults.STRATEGY_HUD_RENDERER_VERSION;
  public int trackerNormalColor = ConfigDefaults.TRACKER_NORMAL_COLOR;
  public int trackerAutograbbingColor = ConfigDefaults.TRACKER_AUTOGRABBING_COLOR;
  public int trackerRidingColor = ConfigDefaults.TRACKER_RIDING_COLOR;
  public int trackerErrorColor = ConfigDefaults.TRACKER_ERROR_COLOR;
  public int trackerClosestRideColor = ConfigDefaults.TRACKER_CLOSEST_RIDE_COLOR;
  public ClosestRideMode closestRideMode = ConfigDefaults.CLOSEST_RIDE_MODE;
  public AudioBoostReminderMode audioBoostReminderMode = ConfigDefaults.AUDIO_BOOST_REMINDER_MODE;
  public ClosedCaptionMode closedCaptionMode = ConfigDefaults.CLOSED_CAPTION_MODE;
  public HideCrosshairMode hideCrosshairMode = ConfigDefaults.HIDE_CROSSHAIR_MODE;
  public TrackerDisplayMode trackerDisplayMode = ConfigDefaults.TRACKER_DISPLAY_MODE;
  public MaxGoal maxGoal = ConfigDefaults.MAX_GOAL;
  public Map<String, RideMaxGoalOverride> rideGoalOverrides = new HashMap<>();
  public SortingRules sortingRules = ConfigDefaults.SORTING_RULES;
  public Map<String, Integer> advanceNoticeSeconds = new HashMap<>();
  public boolean showSessionStats = ConfigDefaults.SHOW_SESSION_STATS;
  public boolean enableOpenAudioMc = ConfigDefaults.ENABLE_OPEN_AUDIO_MC;
  public RideReportNotifyMode rideReportNotifyMode = ConfigDefaults.RIDE_REPORT_NOTIFY_MODE;
  public boolean randomRideOverride = ConfigDefaults.RANDOM_RIDE_OVERRIDE;
  public RideHubMode rideHubMode = ConfigDefaults.RIDE_HUB_MODE;
  public boolean spaceMountainEnhancements = ConfigDefaults.SPACE_MOUNTAIN_ENHANCEMENTS;
  public int rideAudioVolume = ConfigDefaults.RIDE_AUDIO_VOLUME;
  public boolean spaceMountainBanking = ConfigDefaults.SPACE_MOUNTAIN_BANKING;
  public int spaceMountainBankStrength = ConfigDefaults.SPACE_MOUNTAIN_BANK_STRENGTH;

  public int getAdvanceNoticeSeconds(RideName ride) {
    return advanceNoticeSeconds.getOrDefault(ride.toMatchString(), 0);
  }

  /**
   * Returns the effective maximum ride goal for the given ride: the per-ride override if one is set
   * and isn't {@link RideMaxGoalOverride#USE_SYSTEM}, otherwise the global {@link #maxGoal}.
   */
  public int getMaxGoalForRide(RideName ride) {
    RideMaxGoalOverride override = rideGoalOverrides.get(ride.toMatchString());
    if (override != null && override != RideMaxGoalOverride.USE_SYSTEM) {
      return override.getValue();
    }
    return maxGoal.getValue();
  }

  public void resetToDefaults() {
    globalEnable = ConfigDefaults.GLOBAL_ENABLE;
    enabled = ConfigDefaults.ENABLED;
    soundId = ConfigDefaults.SOUND_ID;
    blindWhenRiding = ConfigDefaults.BLIND_WHEN_RIDING;
    fullbrightMode = ConfigDefaults.FULLBRIGHT_MODE;
    cursorReleaseTiming = ConfigDefaults.CURSOR_RELEASE_TIMING;
    silent = ConfigDefaults.SILENT;
    minRideTimeMinutes = ConfigDefaults.MIN_RIDE_TIME_MINUTES;
    rideDisplayCount = ConfigDefaults.RIDE_DISPLAY_COUNT;
    hiddenRides =
        new HashSet<>(
            Arrays.stream(RideName.values())
                .filter(RideName::isSeasonal)
                .map(RideName::toMatchString)
                .collect(Collectors.toSet()));
    hideScoreboard = ConfigDefaults.HIDE_SCOREBOARD;
    hideChat = ConfigDefaults.HIDE_CHAT;
    hideHealth = ConfigDefaults.HIDE_HEALTH;
    hideNameTag = ConfigDefaults.HIDE_NAME_TAG;
    hideHotbar = ConfigDefaults.HIDE_HOTBAR;
    hideExperienceLevel = ConfigDefaults.HIDE_EXPERIENCE_LEVEL;
    onlyAutograbbing = ConfigDefaults.ONLY_AUTOGRABBING;
    hideLovePotionMessages = ConfigDefaults.HIDE_LOVE_POTION_MESSAGES;
    displayShortName = ConfigDefaults.DISPLAY_SHORT_NAME;
    showAutograbRegions = ConfigDefaults.SHOW_AUTOGRAB_REGIONS;
    hudBackgroundOpacity = ConfigDefaults.HUD_BACKGROUND_OPACITY;
    minimizeWindow = ConfigDefaults.MINIMIZE_WINDOW;
    hibernationWhenRiding = ConfigDefaults.HIBERNATION_WHEN_RIDING;
    strategyHudRendererVersion = ConfigDefaults.STRATEGY_HUD_RENDERER_VERSION;
    trackerNormalColor = ConfigDefaults.TRACKER_NORMAL_COLOR;
    trackerAutograbbingColor = ConfigDefaults.TRACKER_AUTOGRABBING_COLOR;
    trackerRidingColor = ConfigDefaults.TRACKER_RIDING_COLOR;
    trackerErrorColor = ConfigDefaults.TRACKER_ERROR_COLOR;
    trackerClosestRideColor = ConfigDefaults.TRACKER_CLOSEST_RIDE_COLOR;
    closestRideMode = ConfigDefaults.CLOSEST_RIDE_MODE;
    audioBoostReminderMode = ConfigDefaults.AUDIO_BOOST_REMINDER_MODE;
    closedCaptionMode = ConfigDefaults.CLOSED_CAPTION_MODE;
    hideCrosshairMode = ConfigDefaults.HIDE_CROSSHAIR_MODE;
    trackerDisplayMode = ConfigDefaults.TRACKER_DISPLAY_MODE;
    maxGoal = ConfigDefaults.MAX_GOAL;
    rideGoalOverrides = new HashMap<>();
    sortingRules = ConfigDefaults.SORTING_RULES;
    advanceNoticeSeconds = new HashMap<>();
    showSessionStats = ConfigDefaults.SHOW_SESSION_STATS;
    enableOpenAudioMc = ConfigDefaults.ENABLE_OPEN_AUDIO_MC;
    rideReportNotifyMode = ConfigDefaults.RIDE_REPORT_NOTIFY_MODE;
    randomRideOverride = ConfigDefaults.RANDOM_RIDE_OVERRIDE;
    rideHubMode = ConfigDefaults.RIDE_HUB_MODE;
    spaceMountainEnhancements = ConfigDefaults.SPACE_MOUNTAIN_ENHANCEMENTS;
    rideAudioVolume = ConfigDefaults.RIDE_AUDIO_VOLUME;
    spaceMountainBanking = ConfigDefaults.SPACE_MOUNTAIN_BANKING;
    spaceMountainBankStrength = ConfigDefaults.SPACE_MOUNTAIN_BANK_STRENGTH;
  }

  public ConfigSetting copy() {
    ConfigSetting copy = new ConfigSetting();
    copy.globalEnable = this.globalEnable;
    copy.enabled = this.enabled;
    copy.soundId = this.soundId;
    copy.blindWhenRiding = this.blindWhenRiding;
    copy.fullbrightMode = this.fullbrightMode;
    copy.cursorReleaseTiming = this.cursorReleaseTiming;
    copy.silent = this.silent;
    copy.minRideTimeMinutes = this.minRideTimeMinutes;
    copy.rideDisplayCount = this.rideDisplayCount;
    copy.hiddenRides = this.hiddenRides != null ? new HashSet<>(this.hiddenRides) : new HashSet<>();
    copy.hideScoreboard = this.hideScoreboard;
    copy.hideChat = this.hideChat;
    copy.hideHealth = this.hideHealth;
    copy.hideNameTag = this.hideNameTag;
    copy.hideHotbar = this.hideHotbar;
    copy.hideExperienceLevel = this.hideExperienceLevel;
    copy.onlyAutograbbing = this.onlyAutograbbing;
    copy.hideLovePotionMessages = this.hideLovePotionMessages;
    copy.displayShortName = this.displayShortName;
    copy.showAutograbRegions = this.showAutograbRegions;
    copy.hudBackgroundOpacity = this.hudBackgroundOpacity;
    copy.minimizeWindow = this.minimizeWindow;
    copy.hibernationWhenRiding = this.hibernationWhenRiding;
    copy.strategyHudRendererVersion = this.strategyHudRendererVersion;
    copy.trackerNormalColor = this.trackerNormalColor;
    copy.trackerAutograbbingColor = this.trackerAutograbbingColor;
    copy.trackerRidingColor = this.trackerRidingColor;
    copy.trackerErrorColor = this.trackerErrorColor;
    copy.trackerClosestRideColor = this.trackerClosestRideColor;
    copy.closestRideMode = this.closestRideMode;
    copy.audioBoostReminderMode = this.audioBoostReminderMode;
    copy.closedCaptionMode = this.closedCaptionMode;
    copy.hideCrosshairMode = this.hideCrosshairMode;
    copy.trackerDisplayMode = this.trackerDisplayMode;
    copy.maxGoal = this.maxGoal;
    copy.rideGoalOverrides =
        this.rideGoalOverrides != null ? new HashMap<>(this.rideGoalOverrides) : new HashMap<>();
    copy.sortingRules = this.sortingRules;
    copy.advanceNoticeSeconds =
        this.advanceNoticeSeconds != null
            ? new HashMap<>(this.advanceNoticeSeconds)
            : new HashMap<>();
    copy.showSessionStats = this.showSessionStats;
    copy.enableOpenAudioMc = this.enableOpenAudioMc;
    copy.rideReportNotifyMode = this.rideReportNotifyMode;
    copy.randomRideOverride = this.randomRideOverride;
    copy.rideHubMode = this.rideHubMode;
    copy.spaceMountainEnhancements = this.spaceMountainEnhancements;
    copy.rideAudioVolume = this.rideAudioVolume;
    copy.spaceMountainBanking = this.spaceMountainBanking;
    copy.spaceMountainBankStrength = this.spaceMountainBankStrength;
    return copy;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ConfigSetting that = (ConfigSetting) o;
    return globalEnable == that.globalEnable
        && enabled == that.enabled
        && blindWhenRiding == that.blindWhenRiding
        && fullbrightMode == that.fullbrightMode
        && cursorReleaseTiming == that.cursorReleaseTiming
        && silent == that.silent
        && rideDisplayCount == that.rideDisplayCount
        && hideScoreboard == that.hideScoreboard
        && hideChat == that.hideChat
        && hideHealth == that.hideHealth
        && hideNameTag == that.hideNameTag
        && hideHotbar == that.hideHotbar
        && hideExperienceLevel == that.hideExperienceLevel
        && onlyAutograbbing == that.onlyAutograbbing
        && displayShortName == that.displayShortName
        && showAutograbRegions == that.showAutograbRegions
        && hudBackgroundOpacity == that.hudBackgroundOpacity
        && minimizeWindow == that.minimizeWindow
        && hibernationWhenRiding == that.hibernationWhenRiding
        && hideLovePotionMessages == that.hideLovePotionMessages
        && strategyHudRendererVersion == that.strategyHudRendererVersion
        && trackerNormalColor == that.trackerNormalColor
        && trackerAutograbbingColor == that.trackerAutograbbingColor
        && trackerRidingColor == that.trackerRidingColor
        && trackerErrorColor == that.trackerErrorColor
        && trackerClosestRideColor == that.trackerClosestRideColor
        && closestRideMode == that.closestRideMode
        && audioBoostReminderMode == that.audioBoostReminderMode
        && closedCaptionMode == that.closedCaptionMode
        && hideCrosshairMode == that.hideCrosshairMode
        && trackerDisplayMode == that.trackerDisplayMode
        && maxGoal == that.maxGoal
        && sortingRules == that.sortingRules
        && showSessionStats == that.showSessionStats
        && enableOpenAudioMc == that.enableOpenAudioMc
        && rideReportNotifyMode == that.rideReportNotifyMode
        && randomRideOverride == that.randomRideOverride
        && rideHubMode == that.rideHubMode
        && spaceMountainEnhancements == that.spaceMountainEnhancements
        && rideAudioVolume == that.rideAudioVolume
        && spaceMountainBanking == that.spaceMountainBanking
        && spaceMountainBankStrength == that.spaceMountainBankStrength
        && Objects.equals(soundId, that.soundId)
        && Objects.equals(minRideTimeMinutes, that.minRideTimeMinutes)
        && Objects.equals(hiddenRides, that.hiddenRides)
        && Objects.equals(rideGoalOverrides, that.rideGoalOverrides)
        && Objects.equals(advanceNoticeSeconds, that.advanceNoticeSeconds);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        globalEnable,
        enabled,
        soundId,
        blindWhenRiding,
        fullbrightMode,
        cursorReleaseTiming,
        silent,
        minRideTimeMinutes,
        rideDisplayCount,
        hiddenRides,
        hideScoreboard,
        hideChat,
        hideHealth,
        hideNameTag,
        hideHotbar,
        hideExperienceLevel,
        onlyAutograbbing,
        displayShortName,
        showAutograbRegions,
        hudBackgroundOpacity,
        minimizeWindow,
        hibernationWhenRiding,
        hideLovePotionMessages,
        strategyHudRendererVersion,
        trackerNormalColor,
        trackerAutograbbingColor,
        trackerRidingColor,
        trackerErrorColor,
        trackerClosestRideColor,
        closestRideMode,
        audioBoostReminderMode,
        closedCaptionMode,
        hideCrosshairMode,
        trackerDisplayMode,
        maxGoal,
        rideGoalOverrides,
        sortingRules,
        advanceNoticeSeconds,
        showSessionStats,
        enableOpenAudioMc,
        rideReportNotifyMode,
        randomRideOverride,
        rideHubMode,
        spaceMountainEnhancements,
        rideAudioVolume,
        spaceMountainBanking,
        spaceMountainBankStrength);
  }
}
