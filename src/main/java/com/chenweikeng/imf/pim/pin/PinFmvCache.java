package com.chenweikeng.imf.pim.pin;

import com.chenweikeng.imf.pim.PimClient;
import java.io.File;

/**
 * Cleanup for the legacy FMV cache file ({@code config/pim_fmv.json}).
 *
 * <p>The old {@code /pim:fmv} command persisted computed Fair Market Values to disk. It was
 * superseded by the {@code /pim} GUI, which recomputes FMV on demand via {@link
 * PinCalculationUtils#calculateFMVValuesForSeries} and never reads or writes this file. The only
 * remaining purpose is to delete the stale file when the user resets pin data, so installs that
 * predate the GUI don't leave it lying around.
 */
public final class PinFmvCache {
  private static final File DATA_FILE = new File("config/pim_fmv.json");

  private PinFmvCache() {}

  /** Deletes the legacy FMV cache file if it exists. */
  public static void resetCache() {
    if (!DATA_FILE.exists()) {
      return;
    }
    if (DATA_FILE.delete()) {
      PimClient.LOGGER.info("[Pim] FMV cache file deleted successfully");
    } else {
      PimClient.LOGGER.warn("[Pim] Failed to delete FMV cache file");
    }
  }
}
