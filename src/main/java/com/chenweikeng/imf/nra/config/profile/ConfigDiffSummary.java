package com.chenweikeng.imf.nra.config.profile;

import com.chenweikeng.imf.nra.config.ConfigSetting;
import com.chenweikeng.imf.nra.config.ModConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Generates a brief natural-language description of a ConfigSetting by comparing it to the closest
 * known profile (built-in, saved, or defaults).
 */
public final class ConfigDiffSummary {
  private static final int MAX_DESCRIPTION_LENGTH = 60;

  private ConfigDiffSummary() {}

  /**
   * Returns a brief description like "Grinding + tracker off, chat shown" or "Default settings".
   */
  public static String describe(ConfigSetting setting) {
    if (setting == null) return "Unknown";

    // Find the closest match among built-in profiles, saved profiles, and defaults
    String closestName = null;
    List<String> closestDiffs = null;
    int closestDiffCount = Integer.MAX_VALUE;

    // Check built-in profiles
    for (StoredProfile builtIn : BuiltInProfiles.all()) {
      if (setting.equals(builtIn.data)) {
        return builtIn.name;
      }
      List<String> diffs = computeDiffs(setting, builtIn.data);
      if (diffs.size() < closestDiffCount) {
        closestDiffCount = diffs.size();
        closestName = builtIn.name;
        closestDiffs = diffs;
      }
    }

    // Check saved profiles
    for (StoredProfile saved : ProfileManager.getAllProfiles()) {
      if (saved.data == null) continue;
      if (setting.equals(saved.data)) {
        return saved.name;
      }
      List<String> diffs = computeDiffs(setting, saved.data);
      if (diffs.size() < closestDiffCount) {
        closestDiffCount = diffs.size();
        closestName = saved.name;
        closestDiffs = diffs;
      }
    }

    // Check current settings
    ConfigSetting current = ModConfig.currentSetting;
    if (current != null && setting.equals(current)) {
      return "Current settings";
    }
    if (current != null) {
      List<String> currentDiffs = computeDiffs(setting, current);
      if (currentDiffs.size() < closestDiffCount) {
        closestDiffCount = currentDiffs.size();
        closestName = "Current";
        closestDiffs = currentDiffs;
      }
    }

    // Check defaults
    ConfigSetting defaults = new ConfigSetting();
    if (setting.equals(defaults)) {
      return "Default settings";
    }
    List<String> defaultDiffs = computeDiffs(setting, defaults);
    if (defaultDiffs.size() < closestDiffCount) {
      closestDiffCount = defaultDiffs.size();
      closestName = "Default";
      closestDiffs = defaultDiffs;
    }

    if (closestDiffs == null || closestDiffs.isEmpty()) {
      return closestName != null ? closestName : "Custom";
    }

    // Build description: "ClosestName + diff1, diff2..."
    StringBuilder sb = new StringBuilder();
    sb.append(closestName).append(" + ");
    truncatedJoin(sb, closestDiffs);
    return sb.toString();
  }

  private static void truncatedJoin(StringBuilder sb, List<String> items) {
    int startLen = sb.length();
    for (int i = 0; i < items.size(); i++) {
      String item = items.get(i);
      int remaining = items.size() - i - 1;
      String suffix = remaining > 0 ? ", ..." : "";

      if (sb.length() + item.length() + (i > 0 ? 2 : 0) + suffix.length()
          > MAX_DESCRIPTION_LENGTH + startLen) {
        if (i > 0) {
          sb.append(", ...");
        } else {
          sb.append(
              item,
              0,
              Math.min(item.length(), MAX_DESCRIPTION_LENGTH - sb.length() + startLen - 3));
          sb.append("...");
        }
        return;
      }
      if (i > 0) sb.append(", ");
      sb.append(item);
    }
  }

  private static List<String> computeDiffs(ConfigSetting a, ConfigSetting b) {
    List<String> diffs = new ArrayList<>();

    diffBool(diffs, "enabled", a.enabled, b.enabled);
    diffBool(diffs, "global", a.globalEnable, b.globalEnable);
    diffBool(diffs, "blind", a.blindWhenRiding, b.blindWhenRiding);
    diffBool(diffs, "silent", a.silent, b.silent);
    diffBool(diffs, "scoreboard", a.hideScoreboard, b.hideScoreboard);
    diffBool(diffs, "chat", a.hideChat, b.hideChat);
    diffBool(diffs, "health", a.hideHealth, b.hideHealth);
    diffBool(diffs, "nametag", a.hideNameTag, b.hideNameTag);
    diffBool(diffs, "hotbar", a.hideHotbar, b.hideHotbar);
    diffBool(diffs, "XP bar", a.hideExperienceLevel, b.hideExperienceLevel);
    diffBool(diffs, "love msgs", a.hideLovePotionMessages, b.hideLovePotionMessages);
    diffBool(diffs, "autograb only", a.onlyAutograbbing, b.onlyAutograbbing);
    diffBool(diffs, "short names", a.displayShortName, b.displayShortName);
    diffBool(diffs, "autograb regions", a.showAutograbRegions, b.showAutograbRegions);
    diffBool(diffs, "hibernate", a.hibernationWhenRiding, b.hibernationWhenRiding);
    diffBool(diffs, "stats", a.showSessionStats, b.showSessionStats);
    diffBool(diffs, "OpenAudioMC", a.enableOpenAudioMc, b.enableOpenAudioMc);
    if (a.fullbrightMode != b.fullbrightMode) {
      diffs.add("fullbright " + a.fullbrightMode.name().toLowerCase());
    }
    if (a.cursorReleaseTiming != b.cursorReleaseTiming) {
      diffs.add("cursor " + a.cursorReleaseTiming.name().toLowerCase());
    }
    if (a.minimizeWindow != b.minimizeWindow) {
      diffs.add("minimize " + a.minimizeWindow.name().toLowerCase());
    }
    if (a.trackerDisplayMode != b.trackerDisplayMode) {
      diffs.add("tracker " + a.trackerDisplayMode.name().toLowerCase());
    }
    if (a.closestRideMode != b.closestRideMode) {
      diffs.add("closest " + a.closestRideMode.name().toLowerCase());
    }
    if (a.strategyHudRendererVersion != b.strategyHudRendererVersion) {
      diffs.add("HUD " + a.strategyHudRendererVersion.name());
    }
    if (a.closedCaptionMode != b.closedCaptionMode) {
      diffs.add("captions " + a.closedCaptionMode.name().toLowerCase());
    }
    if (a.hideCrosshairMode != b.hideCrosshairMode) {
      diffs.add("crosshair " + a.hideCrosshairMode.name().toLowerCase());
    }
    if (a.audioBoostReminderMode != b.audioBoostReminderMode) {
      diffs.add("audio boost " + a.audioBoostReminderMode.name().toLowerCase());
    }
    if (a.maxGoal != b.maxGoal) {
      diffs.add("goal " + a.maxGoal.name());
    }
    if (a.sortingRules != b.sortingRules) {
      diffs.add("sort " + a.sortingRules.name().toLowerCase());
    }
    if (a.rideDisplayCount != b.rideDisplayCount) {
      diffs.add("show " + a.rideDisplayCount + " rides");
    }
    if (!Objects.equals(a.minRideTimeMinutes, b.minRideTimeMinutes)) {
      diffs.add("min time " + a.minRideTimeMinutes + "m");
    }
    if (a.hudBackgroundOpacity != b.hudBackgroundOpacity) {
      diffs.add("opacity " + a.hudBackgroundOpacity + "%");
    }
    if (!Objects.equals(a.soundId, b.soundId)) {
      diffs.add("sound changed");
    }
    if (!Objects.equals(a.hiddenRides, b.hiddenRides)) {
      diffs.add("hidden rides changed");
    }
    if (!Objects.equals(a.advanceNoticeSeconds, b.advanceNoticeSeconds)) {
      diffs.add("advance notice changed");
    }
    // Skip color diffs — too noisy for a brief summary

    return diffs;
  }

  private static void diffBool(List<String> diffs, String label, boolean a, boolean b) {
    if (a != b) {
      // For "hide X" booleans, saying "chat shown" vs "chat hidden" is clearer
      if (label.equals("chat")
          || label.equals("scoreboard")
          || label.equals("health")
          || label.equals("nametag")
          || label.equals("hotbar")
          || label.equals("XP bar")) {
        diffs.add(label + (a ? " hidden" : " shown"));
      } else {
        diffs.add(label + (a ? " on" : " off"));
      }
    }
  }
}
