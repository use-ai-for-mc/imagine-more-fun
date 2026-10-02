package com.chenweikeng.imf.nra.dailyplan;

import static org.junit.jupiter.api.Assertions.*;

import com.chenweikeng.imf.nra.ride.RideName;
import com.chenweikeng.imf.nra.ride.SeasonalRideSchedule;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DailyPlanSeasonalRidesTest {
  @Test
  void guardiansChangesAtOctoberBoundariesEveryYear() {
    for (int year : new int[] {2026, 2027}) {
      assertEquals(RideName.GUARDIANS_OF_THE_GALAXY_MISSION_BREAKOUT, guardians(year, 9, 30));
      assertEquals(RideName.GUARDIANS_OF_THE_GALAXY_MONSTERS_AFTER_DARK, guardians(year, 10, 1));
      assertEquals(RideName.GUARDIANS_OF_THE_GALAXY_MONSTERS_AFTER_DARK, guardians(year, 10, 31));
      assertEquals(RideName.GUARDIANS_OF_THE_GALAXY_MISSION_BREAKOUT, guardians(year, 11, 1));
    }
  }

  private RideName guardians(int year, int month, int day) {
    return SeasonalRideSchedule.forDate(
        RideName.GUARDIANS_OF_THE_GALAXY_MONSTERS_AFTER_DARK, LocalDate.of(year, month, day));
  }

  @Test
  void mansionIncludesJanuary15AndReturnsToOrdinaryOnJanuary16() {
    assertEquals(RideName.HAUNTED_MANSION, mansion(2026, 9, 30));
    assertEquals(RideName.HAUNTED_MANSION_HOLIDAY, mansion(2026, 10, 1));
    assertEquals(RideName.HAUNTED_MANSION_HOLIDAY, mansion(2026, 12, 31));
    assertEquals(RideName.HAUNTED_MANSION_HOLIDAY, mansion(2027, 1, 1));
    assertEquals(RideName.HAUNTED_MANSION_HOLIDAY, mansion(2027, 1, 15));
    assertEquals(RideName.HAUNTED_MANSION, mansion(2027, 1, 16));
    assertEquals(
        RideName.HYPERSPACE_MOUNTAIN,
        SeasonalRideSchedule.forDate(RideName.HYPERSPACE_MOUNTAIN, LocalDate.of(2026, 10, 1)));
  }

  private RideName mansion(int year, int month, int day) {
    return SeasonalRideSchedule.forDate(RideName.HAUNTED_MANSION, LocalDate.of(year, month, day));
  }

  @Test
  void defaultAndPreviouslySwitchedVisibilityAllowFamilyButHidingBothExcludesIt() {
    assertFalse(
        SeasonalRideSchedule.isHidden(
            RideName.HAUNTED_MANSION_HOLIDAY, Set.of("Haunted Mansion Holiday")));
    assertFalse(SeasonalRideSchedule.isHidden(RideName.HAUNTED_MANSION, Set.of("Haunted Mansion")));
    assertTrue(
        SeasonalRideSchedule.isHidden(
            RideName.HAUNTED_MANSION_HOLIDAY,
            Set.of("Haunted Mansion", "Haunted Mansion Holiday")));
  }

  @Test
  void changesExistingNodeWithIndependentBaselineAndPreservesHistoryAndServerQuests() {
    DailyPlan plan = new DailyPlan();
    DailyPlanLayer pending = layer(RideName.HAUNTED_MANSION);
    pending.baselineCounts = new HashMap<>();
    pending.baselineCounts.put("Haunted Mansion", 698);
    DailyPlanLayer history = layer(RideName.HAUNTED_MANSION);
    history.completed = true;
    DailyPlanLayer quest = layer(RideName.HAUNTED_MANSION);
    quest.fromDailyQuest = true;
    plan.layers = new ArrayList<>(List.of(history, pending, quest));

    assertTrue(DailyPlanSeasonalRides.updatePlan(plan, LocalDate.of(2026, 10, 2), ride -> 162));
    assertEquals("Haunted Mansion Holiday", pending.nodes.getFirst().ride);
    assertEquals(162, pending.baselineCounts.get("Haunted Mansion Holiday"));
    assertEquals(698, pending.baselineCounts.get("Haunted Mansion"));
    assertEquals("Haunted Mansion", history.nodes.getFirst().ride);
    assertEquals("Haunted Mansion", quest.nodes.getFirst().ride);
    assertFalse(DailyPlanSeasonalRides.updatePlan(plan, LocalDate.of(2026, 10, 2), ride -> 999));
    assertEquals(162, pending.baselineCounts.get("Haunted Mansion Holiday"));
  }

  @Test
  void avoidsDuplicateVariantsInExistingLayer() {
    DailyPlan plan = new DailyPlan();
    DailyPlanLayer layer = layer(RideName.HAUNTED_MANSION);
    layer.nodes.add(new DailyPlanNode("Haunted Mansion Holiday", 3));
    layer.baselineCounts = new HashMap<>();
    layer.baselineCounts.put("Haunted Mansion Holiday", 160);
    plan.layers = List.of(layer);
    assertTrue(DailyPlanSeasonalRides.updatePlan(plan, LocalDate.of(2026, 10, 2), ride -> 162));
    assertEquals(1, layer.nodes.size());
    assertEquals(3, layer.nodes.getFirst().k);
    assertEquals(160, layer.baselineCounts.get("Haunted Mansion Holiday"));
  }

  private DailyPlanLayer layer(RideName ride) {
    return new DailyPlanLayer(
        DailyPlanLayer.LayerType.SINGLE,
        new ArrayList<>(List.of(new DailyPlanNode(ride.toMatchString(), 3))));
  }
}
