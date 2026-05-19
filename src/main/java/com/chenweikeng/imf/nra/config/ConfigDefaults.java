package com.chenweikeng.imf.nra.config;

public final class ConfigDefaults {
  private ConfigDefaults() {}

  public static final boolean GLOBAL_ENABLE = true;
  public static final boolean ENABLED = true;
  public static final String SOUND_ID = "entity.experience_orb.pickup";
  public static final boolean BLIND_WHEN_RIDING = true;
  public static final FullbrightMode FULLBRIGHT_MODE = FullbrightMode.NONE;
  public static final CursorReleaseTiming CURSOR_RELEASE_TIMING = CursorReleaseTiming.NONE;
  public static final boolean SILENT = true;
  public static final int RIDE_DISPLAY_COUNT = 10;
  public static final boolean HIDE_SCOREBOARD = false;
  public static final boolean HIDE_CHAT = false;
  public static final boolean HIDE_HEALTH = true;
  public static final boolean HIDE_NAME_TAG = false;
  public static final boolean HIDE_HOTBAR = false;
  public static final boolean HIDE_EXPERIENCE_LEVEL = false;
  public static final boolean ONLY_AUTOGRABBING = false;
  public static final boolean HIDE_LOVE_POTION_MESSAGES = false;
  public static final boolean DISPLAY_SHORT_NAME = false;
  public static final boolean SHOW_AUTOGRAB_REGIONS = false;
  public static final int HUD_BACKGROUND_OPACITY = 80;
  public static final WindowMinimizeTiming MINIMIZE_WINDOW = WindowMinimizeTiming.NONE;
  public static final boolean HIBERNATION_WHEN_RIDING = true;
  public static final StrategyHudRendererVersion STRATEGY_HUD_RENDERER_VERSION =
      StrategyHudRendererVersion.V2;
  public static final Integer MIN_RIDE_TIME_MINUTES = 0;

  public static final int TRACKER_NORMAL_COLOR = 0xFFFFFFFF;
  public static final int TRACKER_AUTOGRABBING_COLOR = 0xFFEE00FF;
  public static final int TRACKER_RIDING_COLOR = 0xFF00FF00;
  public static final int TRACKER_ERROR_COLOR = 0xFFFF6600;
  public static final int TRACKER_CLOSEST_RIDE_COLOR = 0xFFFFFF00;
  public static final ClosestRideMode CLOSEST_RIDE_MODE = ClosestRideMode.ONLY_IN_PROGRESS;
  public static final AudioBoostReminderMode AUDIO_BOOST_REMINDER_MODE =
      AudioBoostReminderMode.ONLY_WHEN_RIDING;
  public static final ClosedCaptionMode CLOSED_CAPTION_MODE = ClosedCaptionMode.RECOLORED;
  public static final HideCrosshairMode HIDE_CROSSHAIR_MODE = HideCrosshairMode.NONE;
  public static final TrackerDisplayMode TRACKER_DISPLAY_MODE = TrackerDisplayMode.ALWAYS;
  public static final MaxGoal MAX_GOAL = MaxGoal.K1;
  public static final SortingRules SORTING_RULES = SortingRules.NEXT_GOAL_ASC;
  public static final boolean SHOW_SESSION_STATS = true;
  public static final boolean ENABLE_OPEN_AUDIO_MC = true;
  public static final RideReportNotifyMode RIDE_REPORT_NOTIFY_MODE = RideReportNotifyMode.POPUP;
  public static final boolean RANDOM_RIDE_OVERRIDE = true;
  public static final RideHubMode RIDE_HUB_MODE = RideHubMode.RIDE_PLAN;
  public static final boolean SPACE_MOUNTAIN_ENHANCEMENTS = true;
}
