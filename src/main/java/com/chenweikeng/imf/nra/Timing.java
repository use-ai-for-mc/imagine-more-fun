package com.chenweikeng.imf.nra;

public final class Timing {
  public static final int ALERT_CHECK_INTERVAL = 200;
  public static final int RIDE_COMPLETION_SUPPRESSION_TICKS = 100;
  public static final int VEHICLE_SUPPRESSION_TICKS = 100;
  public static final int MOVEMENT_SUPPRESSION_TICKS = 600;
  public static final int REMINDER_INTERVAL_TICKS = 300;
  public static final int HUD_UPDATE_INTERVAL_TICKS = 40;
  public static final int CANOE_MESSAGE_COOLDOWN_TICKS = 200;
  public static final int DYNAMIC_FPS_MESSAGE_COOLDOWN_TICKS = 12000;
  // Small delay after entering the autograb zone before minimizing the window,
  // so the player has a moment to walk away from the risky border of the zone.
  public static final int ZONE_ENTRY_MINIMIZE_DELAY_TICKS = 5;
  // Interval for re-issuing the OS attention request (macOS Dock bounce / Windows taskbar
  // flash) while the not-riding alert keeps firing and the window stays unfocused.
  public static final int SYSTEM_ATTENTION_REPEAT_TICKS = 50;

  private Timing() {}
}
