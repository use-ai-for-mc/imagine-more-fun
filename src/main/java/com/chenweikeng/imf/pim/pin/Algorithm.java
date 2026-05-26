package com.chenweikeng.imf.pim.pin;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class Algorithm {

  public static class DPResult {
    public final Optional<AlgorithmError> error;
    public final Optional<Double> value;
    public final Optional<Double> whatIfOneMoreSignature;
    public final Optional<Double> whatIfOneMoreDeluxe;
    public final Optional<Double> whatIfOneMoreRare;
    public final Optional<Double> whatIfOneMoreUncommon;
    public final Optional<Double> whatIfOneMoreCommon;
    public final Optional<Double> whatIfLastSignature;
    public final Optional<Double> whatIfLastDeluxe;
    public final Optional<Double> whatIfLastRare;
    public final Optional<Double> whatIfLastUncommon;
    public final Optional<Double> whatIfLastCommon;

    private DPResult(
        Optional<AlgorithmError> error,
        Optional<Double> value,
        Optional<Double> whatIfOneMoreSignature,
        Optional<Double> whatIfOneMoreDeluxe,
        Optional<Double> whatIfOneMoreRare,
        Optional<Double> whatIfOneMoreUncommon,
        Optional<Double> whatIfOneMoreCommon,
        Optional<Double> whatIfLastSignature,
        Optional<Double> whatIfLastDeluxe,
        Optional<Double> whatIfLastRare,
        Optional<Double> whatIfLastUncommon,
        Optional<Double> whatIfLastCommon) {
      this.error = error;
      this.value = value;
      this.whatIfOneMoreSignature = whatIfOneMoreSignature;
      this.whatIfOneMoreDeluxe = whatIfOneMoreDeluxe;
      this.whatIfOneMoreRare = whatIfOneMoreRare;
      this.whatIfOneMoreUncommon = whatIfOneMoreUncommon;
      this.whatIfOneMoreCommon = whatIfOneMoreCommon;
      this.whatIfLastSignature = whatIfLastSignature;
      this.whatIfLastDeluxe = whatIfLastDeluxe;
      this.whatIfLastRare = whatIfLastRare;
      this.whatIfLastUncommon = whatIfLastUncommon;
      this.whatIfLastCommon = whatIfLastCommon;
    }

    public static DPResult error(AlgorithmError error) {
      return new DPResult(
          Optional.of(error),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
    }

    public static DPResult success(
        double value,
        Double whatIfOneMoreSignature,
        Double whatIfOneMoreDeluxe,
        Double whatIfOneMoreRare,
        Double whatIfOneMoreUncommon,
        Double whatIfOneMoreCommon,
        Double whatIfLastSignature,
        Double whatIfLastDeluxe,
        Double whatIfLastRare,
        Double whatIfLastUncommon,
        Double whatIfLastCommon) {
      return new DPResult(
          Optional.empty(),
          Optional.of(value),
          Optional.ofNullable(whatIfOneMoreSignature),
          Optional.ofNullable(whatIfOneMoreDeluxe),
          Optional.ofNullable(whatIfOneMoreRare),
          Optional.ofNullable(whatIfOneMoreUncommon),
          Optional.ofNullable(whatIfOneMoreCommon),
          Optional.ofNullable(whatIfLastSignature),
          Optional.ofNullable(whatIfLastDeluxe),
          Optional.ofNullable(whatIfLastRare),
          Optional.ofNullable(whatIfLastUncommon),
          Optional.ofNullable(whatIfLastCommon));
    }

    public static DPResult success(double value) {
      return success(value, null, null, null, null, null, null, null, null, null, null);
    }

    public boolean isError() {
      return error.isPresent();
    }

    public boolean isSuccess() {
      return value.isPresent();
    }
  }

  public static class DPGoal {
    public final int signature;
    public final int deluxe;
    public final int rare;
    public final int uncommon;
    public final int common;

    public final int weightedSum;

    public DPGoal(int signature, int deluxe, int rare, int uncommon, int common) {
      this.signature = signature;
      this.deluxe = deluxe;
      this.rare = rare;
      this.uncommon = uncommon;
      this.common = common;

      this.weightedSum =
          this.signature + this.deluxe * 2 + this.rare * 4 + this.uncommon * 8 + this.common * 16;
    }
  }

  public static class DPStartPoint {
    public final int signature;
    public final int deluxe;
    public final int rare;
    public final int uncommon;
    public final int common;

    public DPStartPoint(int signature, int deluxe, int rare, int uncommon, int common) {
      this.signature = signature;
      this.deluxe = deluxe;
      this.rare = rare;
      this.uncommon = uncommon;
      this.common = common;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      DPStartPoint that = (DPStartPoint) o;
      return signature == that.signature
          && deluxe == that.deluxe
          && rare == that.rare
          && uncommon == that.uncommon
          && common == that.common;
    }

    @Override
    public int hashCode() {
      return Objects.hash(signature, deluxe, rare, uncommon, common);
    }
  }

  public static class PinSeriesCounts {
    public DPGoal goal;
    public DPStartPoint startPoint;

    public PinSeriesCounts(DPGoal goal, DPStartPoint startPoint) {
      this.goal = goal;
      this.startPoint = startPoint;
    }
  }

  public static class DPState {
    public final int signature;
    public final int deluxe;
    public final int rare;
    public final int uncommon;
    public final int common;

    public DPState(int signature, int deluxe, int rare, int uncommon, int common) {
      this.signature = signature;
      this.deluxe = deluxe;
      this.rare = rare;
      this.uncommon = uncommon;
      this.common = common;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      DPState dpState = (DPState) o;
      return signature == dpState.signature
          && deluxe == dpState.deluxe
          && rare == dpState.rare
          && uncommon == dpState.uncommon
          && common == dpState.common;
    }

    @Override
    public int hashCode() {
      return Objects.hash(signature, deluxe, rare, uncommon, common);
    }
  }

  public static DPResult runDynamicProgramming(String seriesName, PinSeriesCounts counts) {
    DPStartPoint startPoint = counts.startPoint;
    DPGoal goal = counts.goal;

    int maxSignature = goal.signature - startPoint.signature;
    int maxDeluxe = goal.deluxe - startPoint.deluxe;
    int maxRare = goal.rare - startPoint.rare;
    int maxUncommon = goal.uncommon - startPoint.uncommon;
    int maxCommon = goal.common - startPoint.common;

    int maxSum = maxSignature + maxDeluxe + maxRare + maxUncommon + maxCommon;

    // Use two layers for space optimization - only current and previous sum layers
    Map<DPState, Double> prevLayer = new HashMap<>();
    Map<DPState, Double> currLayer = new HashMap<>();

    // Capture sum=1 values (expected value of having exactly one pin of each rarity type)
    Double whatIfLastSignature = null;
    Double whatIfLastDeluxe = null;
    Double whatIfLastRare = null;
    Double whatIfLastUncommon = null;
    Double whatIfLastCommon = null;

    for (int sum = 0; sum <= maxSum; sum++) {
      // Clear current layer and swap layers for next iteration
      currLayer.clear();

      for (int s = 0; s <= Math.min(sum, maxSignature); s++) {
        for (int d = 0; d <= Math.min(sum - s, maxDeluxe); d++) {
          for (int r = 0; r <= Math.min(sum - s - d, maxRare); r++) {
            for (int u = 0; u <= Math.min(sum - s - d - r, maxUncommon); u++) {
              int c = sum - s - d - r - u;
              if (c > maxCommon) {
                continue;
              }

              DPState state = new DPState(s, d, r, u, c);

              if (sum == 0) {
                currLayer.put(state, 0d);
              } else {
                double probGetSignature = 0.0;
                double probGetDeluxe = 0.0;
                double probGetRare = 0.0;
                double probGetUncommon = 0.0;
                double probGetCommon = 0.0;

                double expectedValue = 0.0;

                if (s > 0) {
                  DPState prevState = new DPState(s - 1, d, r, u, c);
                  if (prevLayer.containsKey(prevState)) {
                    probGetSignature = calculateProbabilityGetSignature(goal, s);
                    double contribution = probGetSignature * prevLayer.get(prevState);
                    expectedValue += contribution;
                  }
                }

                if (d > 0) {
                  DPState prevState = new DPState(s, d - 1, r, u, c);
                  if (prevLayer.containsKey(prevState)) {
                    probGetDeluxe = calculateProbabilityGetDeluxe(goal, d);
                    double contribution = probGetDeluxe * prevLayer.get(prevState);
                    expectedValue += contribution;
                  }
                }

                if (r > 0) {
                  DPState prevState = new DPState(s, d, r - 1, u, c);
                  if (prevLayer.containsKey(prevState)) {
                    probGetRare = calculateProbabilityGetRare(goal, r);
                    double contribution = probGetRare * prevLayer.get(prevState);
                    expectedValue += contribution;
                  }
                }

                if (u > 0) {
                  DPState prevState = new DPState(s, d, r, u - 1, c);
                  if (prevLayer.containsKey(prevState)) {
                    probGetUncommon = calculateProbabilityGetUncommon(goal, u);
                    double contribution = probGetUncommon * prevLayer.get(prevState);
                    expectedValue += contribution;
                  }
                }

                if (c > 0) {
                  DPState prevState = new DPState(s, d, r, u, c - 1);
                  if (prevLayer.containsKey(prevState)) {
                    probGetCommon = calculateProbabilityGetCommon(goal, c);
                    double contribution = probGetCommon * prevLayer.get(prevState);
                    expectedValue += contribution;
                  }
                }

                double probSum =
                    probGetSignature
                        + probGetDeluxe
                        + probGetRare
                        + probGetUncommon
                        + probGetCommon;

                expectedValue *= 3f;
                expectedValue /= 31f;

                probSum *= 3f;
                probSum /= 31f;

                expectedValue = (expectedValue + 1) / probSum;
                currLayer.put(state, expectedValue);
              }
            }
          }
        }
      }

      // Capture sum=1 values (expected value of having exactly one pin of each rarity type)
      if (sum == 1) {
        DPState oneSignatureState = new DPState(1, 0, 0, 0, 0);
        if (currLayer.containsKey(oneSignatureState)) {
          whatIfLastSignature = currLayer.get(oneSignatureState);
        }
        DPState oneDeluxeState = new DPState(0, 1, 0, 0, 0);
        if (currLayer.containsKey(oneDeluxeState)) {
          whatIfLastDeluxe = currLayer.get(oneDeluxeState);
        }
        DPState oneRareState = new DPState(0, 0, 1, 0, 0);
        if (currLayer.containsKey(oneRareState)) {
          whatIfLastRare = currLayer.get(oneRareState);
        }
        DPState oneUncommonState = new DPState(0, 0, 0, 1, 0);
        if (currLayer.containsKey(oneUncommonState)) {
          whatIfLastUncommon = currLayer.get(oneUncommonState);
        }
        DPState oneCommonState = new DPState(0, 0, 0, 0, 1);
        if (currLayer.containsKey(oneCommonState)) {
          whatIfLastCommon = currLayer.get(oneCommonState);
        }
      }

      // Swap layers for next iteration, but not when we've reached the max sum
      if (sum < maxSum) {
        Map<DPState, Double> temp = prevLayer;
        prevLayer = currLayer;
        currLayer = temp;
      }
    }

    DPState finalState = new DPState(maxSignature, maxDeluxe, maxRare, maxUncommon, maxCommon);
    if (!currLayer.containsKey(finalState)) {
      return DPResult.error(AlgorithmError.DYNAMIC_PROGRAMMING_FAILURE);
    }

    double finalValue = currLayer.get(finalState);

    // Calculate "what if" deltas for each rarity type by looking at previous states
    // The delta represents the savings (finalValue - whatIfValue) - how many draws you'd save
    // by having one more pin of that type
    Double deltaSignature = null;
    Double deltaDeluxe = null;
    Double deltaRare = null;
    Double deltaUncommon = null;
    Double deltaCommon = null;

    // Only calculate deltas if we have room to add one more (i.e., current < goal)
    // The delta is computed as: finalValue - whatIfValue
    if (startPoint.signature < goal.signature && maxSignature > 0) {
      DPState whatIfState =
          new DPState(maxSignature - 1, maxDeluxe, maxRare, maxUncommon, maxCommon);
      Double whatIfValue = prevLayer.get(whatIfState);
      if (whatIfValue != null) {
        deltaSignature = finalValue - whatIfValue;
      }
    }
    if (startPoint.deluxe < goal.deluxe && maxDeluxe > 0) {
      DPState whatIfState =
          new DPState(maxSignature, maxDeluxe - 1, maxRare, maxUncommon, maxCommon);
      Double whatIfValue = prevLayer.get(whatIfState);
      if (whatIfValue != null) {
        deltaDeluxe = finalValue - whatIfValue;
      }
    }
    if (startPoint.rare < goal.rare && maxRare > 0) {
      DPState whatIfState =
          new DPState(maxSignature, maxDeluxe, maxRare - 1, maxUncommon, maxCommon);
      Double whatIfValue = prevLayer.get(whatIfState);
      if (whatIfValue != null) {
        deltaRare = finalValue - whatIfValue;
      }
    }
    if (startPoint.uncommon < goal.uncommon && maxUncommon > 0) {
      DPState whatIfState =
          new DPState(maxSignature, maxDeluxe, maxRare, maxUncommon - 1, maxCommon);
      Double whatIfValue = prevLayer.get(whatIfState);
      if (whatIfValue != null) {
        deltaUncommon = finalValue - whatIfValue;
      }
    }
    if (startPoint.common < goal.common && maxCommon > 0) {
      DPState whatIfState =
          new DPState(maxSignature, maxDeluxe, maxRare, maxUncommon, maxCommon - 1);
      Double whatIfValue = prevLayer.get(whatIfState);
      if (whatIfValue != null) {
        deltaCommon = finalValue - whatIfValue;
      }
    }

    return DPResult.success(
        finalValue,
        deltaSignature,
        deltaDeluxe,
        deltaRare,
        deltaUncommon,
        deltaCommon,
        whatIfLastSignature,
        whatIfLastDeluxe,
        whatIfLastRare,
        whatIfLastUncommon,
        whatIfLastCommon);
  }

  private static double calculateProbabilityGetSignature(DPGoal goal, int currentCount) {
    return (double) currentCount / (double) goal.weightedSum;
  }

  private static double calculateProbabilityGetDeluxe(DPGoal goal, int currentCount) {
    return (double) currentCount * 2 / (double) goal.weightedSum;
  }

  private static double calculateProbabilityGetRare(DPGoal goal, int currentCount) {
    return (double) currentCount * 4 / (double) goal.weightedSum;
  }

  private static double calculateProbabilityGetUncommon(DPGoal goal, int currentCount) {
    return (double) currentCount * 8 / (double) goal.weightedSum;
  }

  private static double calculateProbabilityGetCommon(DPGoal goal, int currentCount) {
    return (double) currentCount * 16 / (double) goal.weightedSum;
  }
}
