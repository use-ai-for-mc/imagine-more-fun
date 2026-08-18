package com.chenweikeng.imf.nra.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TimeFormatUtilTest {
  @Test
  void defaultFormatKeepsDayAndHourBreakdown() {
    assertEquals("2d 3h", TimeFormatUtil.formatDuration(2 * 86_400L + 3 * 3_600L + 14 * 60L));
  }

  @Test
  void totalHoursFormatKeepsMinutesVisibleAfterOneDay() {
    assertEquals(
        "51h 14m", TimeFormatUtil.formatDuration(2 * 86_400L + 3 * 3_600L + 14 * 60L, true));
  }

  @Test
  void totalHoursFormatLeavesShortDurationsUnchanged() {
    assertEquals("14m 30s", TimeFormatUtil.formatDuration(14 * 60L + 30, true));
  }

  @Test
  void totalHoursFormatOmitsZeroMinutes() {
    assertEquals("48h", TimeFormatUtil.formatDuration(2 * 86_400L, true));
  }
}
