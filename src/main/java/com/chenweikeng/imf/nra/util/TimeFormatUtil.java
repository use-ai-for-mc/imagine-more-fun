package com.chenweikeng.imf.nra.util;

public class TimeFormatUtil {
  private static final int SECONDS_PER_MINUTE = 60;
  private static final int SECONDS_PER_HOUR = 3600;
  private static final int SECONDS_PER_DAY = 86400;

  public static String formatDuration(long seconds) {
    return formatDuration(seconds, false);
  }

  /**
   * Formats a duration using the normal day/hour breakdown or, when requested, total hours.
   *
   * <p>The total-hours form keeps the minutes visible for durations longer than a day. For example,
   * 2d 3h 14m is displayed as 51h 14m.
   */
  public static String formatDuration(long seconds, boolean useTotalHours) {
    if (seconds < 0) {
      return "0s";
    }

    long totalHours = seconds / SECONDS_PER_HOUR;
    long minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
    long secs = seconds % SECONDS_PER_MINUTE;

    if (useTotalHours && totalHours > 0) {
      if (minutes == 0) {
        return totalHours + "h";
      }
      return totalHours + "h " + minutes + "m";
    }

    long days = seconds / SECONDS_PER_DAY;
    long hours = (seconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR;

    if (days > 0) {
      return days + "d " + hours + "h";
    } else if (hours > 0) {
      if (minutes == 0) {
        return hours + "h";
      }
      return hours + "h " + minutes + "m";
    } else if (minutes > 0) {
      if (secs == 0) {
        return minutes + "m";
      }
      return minutes + "m " + secs + "s";
    } else {
      return secs + "s";
    }
  }
}
