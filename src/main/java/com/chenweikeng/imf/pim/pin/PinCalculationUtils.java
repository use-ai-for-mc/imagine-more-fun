package com.chenweikeng.imf.pim.pin;

import com.chenweikeng.imf.pim.screen.PinBookHandler;
import com.chenweikeng.imf.pim.screen.PinDetailHandler;
import com.chenweikeng.imf.pim.screen.PinRarityHandler;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class for sharing pin calculation methods between commands. Provides reusable methods for
 * getting pin series counts and calculating player specific values.
 */
public class PinCalculationUtils {

  // Shared cache for player specific start points and results
  private static final ConcurrentHashMap<String, Algorithm.DPStartPoint>
      playerSpecificCachedStartPoints = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, Algorithm.DPResult> playerSpecificCachedResults =
      new ConcurrentHashMap<>();

  // Shared cache for FMV results (start point is always zero, no need to cache)
  private static final ConcurrentHashMap<String, Algorithm.DPResult> fmvCachedResults =
      new ConcurrentHashMap<>();

  /**
   * Gets pin series counts for a given series. Returns null if series data is incomplete or
   * unavailable.
   */
  public static Algorithm.PinSeriesCounts getPinSeriesCounts(String seriesName) {
    try {
      // Get series details
      Map<String, PinDetailHandler.PinDetailEntry> detailMap =
          PinDetailHandler.getInstance().getSeriesDetails(seriesName);
      if (detailMap == null || detailMap.isEmpty()) {
        return null;
      }

      // Get book entry for total counts
      PinBookHandler.PinBookEntry bookEntry = PinBookHandler.getInstance().getBookEntry(seriesName);
      if (bookEntry == null) {
        return null;
      }

      if (bookEntry.totalMints != detailMap.size()) {
        return null;
      }

      // Count pins by rarity
      int signature = 0;
      int deluxe = 0;
      int rare = 0;
      int uncommon = 0;
      int common = 0;

      int mintSignature = 0;
      int mintDeluxe = 0;
      int mintRare = 0;
      int mintUncommon = 0;
      int mintCommon = 0;

      for (Map.Entry<String, PinDetailHandler.PinDetailEntry> entry : detailMap.entrySet()) {
        PinDetailHandler.PinDetailEntry detailEntry = entry.getValue();
        if (detailEntry.rarity == null) {
          continue; // Skip entries without rarity
        }

        switch (detailEntry.rarity) {
          case SIGNATURE:
            signature++;
            if (detailEntry.condition == PinDetailHandler.PinCondition.MINT) {
              mintSignature++;
            }
            break;
          case DELUXE:
            deluxe++;
            if (detailEntry.condition == PinDetailHandler.PinCondition.MINT) {
              mintDeluxe++;
            }
            break;
          case RARE:
            rare++;
            if (detailEntry.condition == PinDetailHandler.PinCondition.MINT) {
              mintRare++;
            }
            break;
          case UNCOMMON:
            uncommon++;
            if (detailEntry.condition == PinDetailHandler.PinCondition.MINT) {
              mintUncommon++;
            }
            break;
          case COMMON:
            common++;
            if (detailEntry.condition == PinDetailHandler.PinCondition.MINT) {
              mintCommon++;
            }
            break;
        }
      }

      // Create goal and start point
      Algorithm.DPGoal goal = new Algorithm.DPGoal(signature, deluxe, rare, uncommon, common);
      Algorithm.DPStartPoint startPoint =
          new Algorithm.DPStartPoint(mintSignature, mintDeluxe, mintRare, mintUncommon, mintCommon);

      Algorithm.PinSeriesCounts counts = new Algorithm.PinSeriesCounts(goal, startPoint);
      return counts;

    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Gets cached algorithm result or runs the algorithm if needed for player specific values.
   * Returns null if calculation fails.
   */
  public static Algorithm.DPResult getCachedOrCalculatePlayerSpecificResult(
      String seriesName, Algorithm.PinSeriesCounts counts) {
    // Check cache for start point
    Algorithm.DPStartPoint cachedStartPoint = playerSpecificCachedStartPoints.get(seriesName);

    Algorithm.DPResult result;
    if (cachedStartPoint != null && cachedStartPoint.equals(counts.startPoint)) {
      result = playerSpecificCachedResults.get(seriesName);
    } else {
      result = Algorithm.runDynamicProgramming(seriesName, counts);
      if (result != null && !result.isError()) {
        playerSpecificCachedStartPoints.put(seriesName, counts.startPoint);
        playerSpecificCachedResults.put(seriesName, result);
      }
    }

    return result;
  }

  /**
   * Gets cached algorithm result or runs the algorithm if needed for FMV values. Returns null if
   * calculation fails.
   */
  public static Algorithm.DPResult getCachedOrCalculateFMVResult(
      String seriesName, Algorithm.PinSeriesCounts counts) {
    // Check cache for result (start point is always zero for FMV)
    Algorithm.DPResult result = fmvCachedResults.get(seriesName);

    if (result == null) {
      result = Algorithm.runDynamicProgramming(seriesName, counts);
      if (result != null && !result.isError()) {
        fmvCachedResults.put(seriesName, result);
      }
    }

    return result;
  }

  /**
   * Calculates player specific values for each rarity type in a series using delta values from the
   * algorithm. Player specific value = (delta * pinbox_price) / 2 Returns empty map if delta values
   * are not available.
   */
  public static Map<Rarity, Double> calculatePlayerSpecificValuesForSeries(String seriesName) {
    Map<Rarity, Double> playerSpecificValues = new HashMap<>();

    // Get series entry to find pinbox price
    PinRarityHandler.PinSeriesEntry seriesEntry =
        PinRarityHandler.getInstance().getSeriesEntry(seriesName);
    if (seriesEntry == null) {
      return playerSpecificValues; // Empty map if no price data
    }
    if (seriesEntry.color == null) {
      return playerSpecificValues; // Empty map if no price data
    }

    double pinboxPrice = seriesEntry.color.price;

    // Get pin series counts
    Algorithm.PinSeriesCounts counts = getPinSeriesCounts(seriesName);
    if (counts == null) {
      return playerSpecificValues; // Empty map if no series data
    }

    // Get cached or calculated result
    Algorithm.DPResult result = getCachedOrCalculatePlayerSpecificResult(seriesName, counts);
    if (result == null) {
      return playerSpecificValues; // Empty map if calculation fails
    }
    if (result.isError()) {
      return playerSpecificValues; // Empty map if calculation fails
    }

    // Calculate player specific values for each rarity type where delta is available
    if (result.whatIfOneMoreSignature.isPresent()) {
      playerSpecificValues.put(
          Rarity.SIGNATURE, (result.whatIfOneMoreSignature.get() * pinboxPrice) / 2.0);
    }
    if (result.whatIfOneMoreDeluxe.isPresent()) {
      playerSpecificValues.put(
          Rarity.DELUXE, (result.whatIfOneMoreDeluxe.get() * pinboxPrice) / 2.0);
    }
    if (result.whatIfOneMoreRare.isPresent()) {
      playerSpecificValues.put(Rarity.RARE, (result.whatIfOneMoreRare.get() * pinboxPrice) / 2.0);
    }
    if (result.whatIfOneMoreUncommon.isPresent()) {
      playerSpecificValues.put(
          Rarity.UNCOMMON, (result.whatIfOneMoreUncommon.get() * pinboxPrice) / 2.0);
    }
    if (result.whatIfOneMoreCommon.isPresent()) {
      playerSpecificValues.put(
          Rarity.COMMON, (result.whatIfOneMoreCommon.get() * pinboxPrice) / 2.0);
    }

    return playerSpecificValues;
  }

  /** Result class for FMV calculations containing both floor and ceiling values. */
  public static class FMVResult {
    public final Map<Rarity, Double> floorValues;
    public final Map<Rarity, Double> ceilValues;

    public FMVResult(Map<Rarity, Double> floorValues, Map<Rarity, Double> ceilValues) {
      this.floorValues = floorValues;
      this.ceilValues = ceilValues;
    }
  }

  /**
   * Calculates FMV values for each rarity type in a series using delta values from the algorithm
   * with zero start point. FMV value = (delta * pinbox_price) / 2 Returns empty maps if delta
   * values are not available.
   *
   * @param seriesName the name of the pin series
   * @return FMVResult containing floorValues (what if one more) and ceilValues (what if last)
   */
  public static FMVResult calculateFMVValuesForSeries(String seriesName) {
    Map<Rarity, Double> floorValues = new HashMap<>();
    Map<Rarity, Double> ceilValues = new HashMap<>();

    // Get series entry to find pinbox price
    PinRarityHandler.PinSeriesEntry seriesEntry =
        PinRarityHandler.getInstance().getSeriesEntry(seriesName);
    if (seriesEntry == null) {
      return new FMVResult(floorValues, ceilValues); // Empty maps if no price data
    }
    if (seriesEntry.color == null) {
      return new FMVResult(floorValues, ceilValues); // Empty maps if no price data
    }

    double pinboxPrice = seriesEntry.color.price;

    // Get pin series counts
    Algorithm.PinSeriesCounts counts = getPinSeriesCounts(seriesName);
    if (counts == null) {
      return new FMVResult(floorValues, ceilValues); // Empty maps if no series data
    }

    // Override start point to zero for FMV calculation
    Algorithm.DPStartPoint zeroStartPoint = new Algorithm.DPStartPoint(0, 0, 0, 0, 0);
    Algorithm.PinSeriesCounts fmvCounts =
        new Algorithm.PinSeriesCounts(counts.goal, zeroStartPoint);

    // Get cached or calculated result
    Algorithm.DPResult result = getCachedOrCalculateFMVResult(seriesName, fmvCounts);
    if (result == null) {
      return new FMVResult(floorValues, ceilValues); // Empty maps if calculation fails
    }
    if (result.isError()) {
      return new FMVResult(floorValues, ceilValues); // Empty maps if calculation fails
    }

    // Calculate floor values (what if one more) for each rarity type where delta is available
    if (result.whatIfOneMoreSignature.isPresent()) {
      floorValues.put(Rarity.SIGNATURE, (result.whatIfOneMoreSignature.get() * pinboxPrice) / 2.0);
    }
    if (result.whatIfOneMoreDeluxe.isPresent()) {
      floorValues.put(Rarity.DELUXE, (result.whatIfOneMoreDeluxe.get() * pinboxPrice) / 2.0);
    }
    if (result.whatIfOneMoreRare.isPresent()) {
      floorValues.put(Rarity.RARE, (result.whatIfOneMoreRare.get() * pinboxPrice) / 2.0);
    }
    if (result.whatIfOneMoreUncommon.isPresent()) {
      floorValues.put(Rarity.UNCOMMON, (result.whatIfOneMoreUncommon.get() * pinboxPrice) / 2.0);
    }
    if (result.whatIfOneMoreCommon.isPresent()) {
      floorValues.put(Rarity.COMMON, (result.whatIfOneMoreCommon.get() * pinboxPrice) / 2.0);
    }

    // Calculate ceil values (what if last) for each rarity type where delta is available
    if (result.whatIfLastSignature.isPresent()) {
      ceilValues.put(Rarity.SIGNATURE, (result.whatIfLastSignature.get() * pinboxPrice) / 2.0);
    }
    if (result.whatIfLastDeluxe.isPresent()) {
      ceilValues.put(Rarity.DELUXE, (result.whatIfLastDeluxe.get() * pinboxPrice) / 2.0);
    }
    if (result.whatIfLastRare.isPresent()) {
      ceilValues.put(Rarity.RARE, (result.whatIfLastRare.get() * pinboxPrice) / 2.0);
    }
    if (result.whatIfLastUncommon.isPresent()) {
      ceilValues.put(Rarity.UNCOMMON, (result.whatIfLastUncommon.get() * pinboxPrice) / 2.0);
    }
    if (result.whatIfLastCommon.isPresent()) {
      ceilValues.put(Rarity.COMMON, (result.whatIfLastCommon.get() * pinboxPrice) / 2.0);
    }

    return new FMVResult(floorValues, ceilValues);
  }
}
