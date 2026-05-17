package com.chenweikeng.imf.nra.config.profile;

import com.chenweikeng.imf.nra.config.*;
import com.chenweikeng.imf.nra.ride.RideName;
import java.util.ArrayList;
import java.util.List;

public final class BuiltInProfiles {

  private BuiltInProfiles() {}

  public static StoredProfile grindingMode() {
    ConfigSetting profile = new ConfigSetting();

    profile.globalEnable = true;
    profile.enabled = true;
    profile.soundId = "entity.experience_orb.pickup";
    profile.silent = true;
    profile.cursorReleaseTiming = CursorReleaseTiming.ON_ZONE_ENTRY;
    profile.minimizeWindow = WindowMinimizeTiming.ON_ZONE_ENTRY;

    profile.blindWhenRiding = true;
    profile.fullbrightMode = FullbrightMode.ALWAYS;
    profile.hideScoreboard = true;
    profile.hideChat = false;
    profile.hideHealth = true;
    profile.hideNameTag = false;
    profile.hideHotbar = false;
    profile.hideExperienceLevel = false;
    profile.hideLovePotionMessages = true;
    profile.closedCaptionMode = ClosedCaptionMode.PLAIN;
    profile.audioBoostReminderMode = AudioBoostReminderMode.ALWAYS;

    profile.trackerDisplayMode = TrackerDisplayMode.ALWAYS;
    profile.strategyHudRendererVersion = StrategyHudRendererVersion.V1;
    profile.rideDisplayCount = 10;
    profile.minRideTimeMinutes = 0;
    profile.maxGoal = MaxGoal.K1;
    profile.hudBackgroundOpacity = 80;
    profile.onlyAutograbbing = false;
    profile.showAutograbRegions = true;

    profile.hibernationWhenRiding = true;
    profile.enableOpenAudioMc = true;

    profile.advanceNoticeSeconds.put(RideName.BIG_THUNDER_MOUNTAIN_RAILROAD.toMatchString(), 5);
    profile.advanceNoticeSeconds.put(RideName.CHIP_N_DALES_GADGET_COASTER.toMatchString(), 5);
    profile.advanceNoticeSeconds.put(RideName.DISNEYLAND_MONORAIL.toMatchString(), 5);
    profile.advanceNoticeSeconds.put(RideName.HEIMLICHS_CHEW_CHEW_TRAIN.toMatchString(), 5);
    profile.advanceNoticeSeconds.put(RideName.INCREDICOASTER.toMatchString(), 5);
    profile.advanceNoticeSeconds.put(RideName.INDIANA_JONES_ADVENTURE.toMatchString(), 5);
    profile.advanceNoticeSeconds.put(RideName.MAIN_STREET_CARRIAGES.toMatchString(), 5);
    profile.advanceNoticeSeconds.put(RideName.RADIATOR_SPRINGS_RACERS.toMatchString(), 5);
    profile.advanceNoticeSeconds.put(RideName.RED_CAR_TROLLEY.toMatchString(), 5);
    profile.advanceNoticeSeconds.put(RideName.SPLASH_MOUNTAIN.toMatchString(), 5);
    profile.advanceNoticeSeconds.put(RideName.TOM_SAWYER_ISLAND_RAFTS.toMatchString(), 5);

    return StoredProfile.create(
        "grinding-mode", "Grinding Mode", "Optimized for efficiency grinding", profile);
  }

  public static StoredProfile sightseeingMode() {
    ConfigSetting profile = new ConfigSetting();

    profile.globalEnable = true;
    profile.enabled = true;
    profile.soundId = "entity.experience_orb.pickup";
    profile.silent = true;
    profile.cursorReleaseTiming = CursorReleaseTiming.ON_ZONE_ENTRY;
    profile.minimizeWindow = WindowMinimizeTiming.NONE;

    profile.blindWhenRiding = false;
    profile.fullbrightMode = FullbrightMode.ALWAYS;
    profile.hideScoreboard = true;
    profile.hideChat = true;
    profile.hideHealth = true;
    profile.hideNameTag = true;
    profile.hideHotbar = true;
    profile.hideExperienceLevel = true;
    profile.hideLovePotionMessages = true;
    profile.closedCaptionMode = ClosedCaptionMode.PLAIN;
    profile.hideCrosshairMode = HideCrosshairMode.ONLY_WHEN_RIDING;
    profile.audioBoostReminderMode = AudioBoostReminderMode.DISABLED;

    profile.trackerDisplayMode = TrackerDisplayMode.NEVER;
    profile.strategyHudRendererVersion = StrategyHudRendererVersion.V2;
    profile.rideDisplayCount = 10;
    profile.minRideTimeMinutes = 0;
    profile.maxGoal = MaxGoal.K1;
    profile.hudBackgroundOpacity = 80;
    profile.onlyAutograbbing = false;

    profile.hibernationWhenRiding = true;
    profile.showSessionStats = false;
    profile.enableOpenAudioMc = true;

    return StoredProfile.create(
        "sightseeing-mode", "Sightseeing Mode", "Clean visuals for enjoying rides", profile);
  }

  public static StoredProfile minimalMode() {
    ConfigSetting profile = new ConfigSetting();

    profile.globalEnable = true;
    profile.enabled = true;
    profile.soundId = "entity.experience_orb.pickup";
    profile.silent = true;
    profile.cursorReleaseTiming = CursorReleaseTiming.ON_VEHICLE_MOUNT;
    profile.minimizeWindow = WindowMinimizeTiming.NONE;

    profile.blindWhenRiding = false;
    profile.fullbrightMode = FullbrightMode.NONE;
    profile.hideScoreboard = false;
    profile.hideChat = false;
    profile.hideHealth = false;
    profile.hideNameTag = false;
    profile.hideHotbar = false;
    profile.hideExperienceLevel = false;
    profile.hideLovePotionMessages = false;
    profile.closedCaptionMode = ClosedCaptionMode.NONE;
    profile.audioBoostReminderMode = AudioBoostReminderMode.DISABLED;

    profile.trackerDisplayMode = TrackerDisplayMode.NEVER;
    profile.strategyHudRendererVersion = StrategyHudRendererVersion.V2;
    profile.rideDisplayCount = 10;
    profile.minRideTimeMinutes = 0;
    profile.maxGoal = MaxGoal.K1;
    profile.hudBackgroundOpacity = 80;
    profile.onlyAutograbbing = false;

    profile.hibernationWhenRiding = true;
    profile.showSessionStats = false;
    profile.enableOpenAudioMc = false;

    return StoredProfile.create(
        "minimal-mode", "Minimal Mode", "Only essential alerts, no visual changes", profile);
  }

  public static List<StoredProfile> all() {
    List<StoredProfile> profiles = new ArrayList<>();
    profiles.add(grindingMode());
    profiles.add(sightseeingMode());
    profiles.add(minimalMode());
    return profiles;
  }
}
