package com.chenweikeng.imf.nra.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chenweikeng.imf.pim.tracker.BossBarTracker;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class QuestCollectibleGlowTest {
  private static final UUID SERVER_EVENT = UUID.fromString("663f64de-6f56-440a-8152-f53ba0fbe3ad");

  private static MutableComponent colored(String text, int color) {
    return Component.literal(text).withStyle(Style.EMPTY.withColor(color));
  }

  private static MutableComponent quest(Component distance) {
    return Component.empty()
        .append(
            colored("Quest: ", 0xFC5371).withStyle(Style.EMPTY.withColor(0xFC5371).withBold(true)))
        .append(colored("Find 5 Honey Pots ", 0x32FF7E))
        .append(distance);
  }

  @Test
  void matchesLiveHoneyPotDistanceAndExcludesLocalPinTraderGuidance() {
    Component title = quest(colored("(20.9) ", 0xC8D6E5)).append(colored("⬇", 0xFFFFFF));
    assertTrue(QuestCollectibleGlow.isDistanceBossBar(SERVER_EVENT, title));
    assertFalse(QuestCollectibleGlow.isDistanceBossBar(BossBarTracker.PIN_TRADER_BOSS_ID, title));
  }

  @Test
  void rejectsQuestCountsAndProgressEvenWithDistanceColor() {
    for (String text :
        new String[] {
          "Quest: Find 5 Honey Pots",
          "Quest: Find 5 Honey Pots ⬅",
          "Quest: Find 5 Honey Pots (2/5)",
          "Quest: Wait (30 seconds)",
          "Pin Trader (1357.5) ⬅",
          "Quest: Find Honey (-1) ⬅",
          ""
        }) {
      assertFalse(
          QuestCollectibleGlow.isDistanceBossBar(SERVER_EVENT, colored(text, 0xC8D6E5)), text);
    }
    assertFalse(QuestCollectibleGlow.isDistanceBossBar(SERVER_EVENT, null));
  }

  @Test
  void acceptsIntegerAndZeroDistancesWithOrWithoutDirection() {
    assertTrue(
        QuestCollectibleGlow.isDistanceBossBar(SERVER_EVENT, quest(colored("(1357) ➡", 0xC8D6E5))));
    assertTrue(
        QuestCollectibleGlow.isDistanceBossBar(SERVER_EVENT, quest(colored("(0.0)", 0xC8D6E5))));
    assertTrue(
        QuestCollectibleGlow.isDistanceBossBar(SERVER_EVENT, quest(colored("(0)", 0xC8D6E5))));
  }

  @Test
  void rejectsCorrectTextWithMissingOrWrongDistanceColor() {
    assertFalse(
        QuestCollectibleGlow.isDistanceBossBar(SERVER_EVENT, quest(Component.literal("(20.9)"))));
    assertFalse(
        QuestCollectibleGlow.isDistanceBossBar(SERVER_EVENT, quest(colored("(20.9)", 0x32FF7E))));
    assertFalse(
        QuestCollectibleGlow.isDistanceBossBar(SERVER_EVENT, quest(colored("(20.9)", 0xFFFFFF))));
    // A gray arrow or gray brackets alone cannot qualify a green number.
    Component mixed =
        colored("(", 0xC8D6E5).append(colored("20.9", 0x32FF7E)).append(colored(") ⬇", 0xC8D6E5));
    assertFalse(QuestCollectibleGlow.isDistanceBossBar(SERVER_EVENT, quest(mixed)));
  }

  @Test
  void handlesInheritedColorAndSplitDistanceComponents() {
    MutableComponent split =
        Component.empty()
            .withStyle(Style.EMPTY.withColor(0xC8D6E5))
            .append(Component.literal("("))
            .append(Component.literal("20"))
            .append(Component.literal(".9"))
            .append(Component.literal(")"));
    assertTrue(QuestCollectibleGlow.isDistanceBossBar(SERVER_EVENT, quest(split)));
    split.append(colored(" ➡", 0xFFFFFF));
    assertTrue(QuestCollectibleGlow.isDistanceBossBar(SERVER_EVENT, quest(split)));
  }

  @Test
  void forcesNightOnlyWhileRenderedQuestDustIsFresh() {
    assertTrue(QuestCollectibleGlow.hasRecentRenderedQuestDust(100, 100));
    assertTrue(QuestCollectibleGlow.hasRecentRenderedQuestDust(120, 100));
    assertFalse(QuestCollectibleGlow.hasRecentRenderedQuestDust(121, 100));
    assertFalse(QuestCollectibleGlow.hasRecentRenderedQuestDust(100, Long.MIN_VALUE));
    assertFalse(QuestCollectibleGlow.hasRecentRenderedQuestDust(99, 100));
  }

  @Test
  void matchesOnlyPureWhiteUnitScaleDust() {
    assertTrue(QuestCollectibleGlow.isMatchingDust(new DustParticleOptions(0xFFFFFF, 1.0F)));
    assertFalse(QuestCollectibleGlow.isMatchingDust(new DustParticleOptions(0xFFFFFE, 1.0F)));
    assertFalse(QuestCollectibleGlow.isMatchingDust(new DustParticleOptions(0xFFFFFF, 2.0F)));
  }

  @Test
  void beamOriginSelectsNearestTrackedEntityInsteadOfMovingDust() {
    Vec3 observer = new Vec3(0, 64, 0);
    Vec3 nearest = new Vec3(3, 100, 4);
    assertEquals(
        nearest,
        QuestCollectibleGlow.nearestHorizontalOrigin(
            List.of(new Vec3(20, 0, 0), nearest, new Vec3(0, -100, 8)), observer));
    assertEquals(null, QuestCollectibleGlow.nearestHorizontalOrigin(List.of(), observer));
  }

  @Test
  void beamOriginTieBreakDoesNotDependOnEntityIterationOrder() {
    Vec3 left = new Vec3(-5, 66, 0);
    Vec3 right = new Vec3(5, 66, 0);
    Vec3 observer = Vec3.ZERO;
    assertEquals(
        left, QuestCollectibleGlow.nearestHorizontalOrigin(List.of(left, right), observer));
    assertEquals(
        left, QuestCollectibleGlow.nearestHorizontalOrigin(List.of(right, left), observer));
  }

  @Test
  void pairsInteractionByOriginNotHitbox() {
    // Live 2026-09-04: Honey Pot and Interaction share an origin; the nearby trash can does not.
    assertTrue(
        QuestCollectibleGlow.isPairedInteractionOrigin(
            254.649, 63.582, -536.404, 254.649, 63.582, -536.404));
    assertFalse(
        QuestCollectibleGlow.isPairedInteractionOrigin(
            255.47489636138715, 65.14999999999993, -535.5356833204487, 254.649, 63.582, -536.404));
  }

  @Test
  void matchesObservedTrophyDustAgainstArmorStandHead() {
    assertTrue(
        QuestCollectibleGlow.isDustNearHead(
            169.90982169507507, 64.17813691670284, -397.83786577455845, 169.488, 62.602, -398.458));
    assertFalse(
        QuestCollectibleGlow.isDustNearHead(
            173.0, 64.17813691670284, -397.83786577455845, 169.488, 62.602, -398.458));
  }

  @Test
  void includesHeadRadiusBoundaryButRejectsDustAboveOrBelowIt() {
    assertTrue(QuestCollectibleGlow.isDustNearHead(0, 3.75, 0, 0, 0, 0));
    assertFalse(QuestCollectibleGlow.isDustNearHead(0, 3.76, 0, 0, 0, 0));
    assertTrue(QuestCollectibleGlow.isDustNearHead(0, -0.75, 0, 0, 0, 0));
    assertFalse(QuestCollectibleGlow.isDustNearHead(0, -0.76, 0, 0, 0, 0));
  }

  @Test
  void matchesLiveYodaExclamationMarkItem() {
    assertTrue(QuestCollectibleGlow.isNpcMarkerItem("minecraft:iron_pickaxe", "Brickhead", 125));
    assertFalse(QuestCollectibleGlow.isNpcMarkerItem("minecraft:iron_pickaxe", "Brickhead", 124));
    assertFalse(
        QuestCollectibleGlow.isNpcMarkerItem("minecraft:diamond_pickaxe", "Brickhead", 125));
    assertFalse(QuestCollectibleGlow.isNpcMarkerItem("minecraft:iron_pickaxe", "Item", 125));
    assertFalse(QuestCollectibleGlow.isNpcMarkerItem("minecraft:iron_pickaxe", null, 125));
  }

  @Test
  void pairsNpcMarkerAboveUnnamedRemotePlayer() {
    assertTrue(QuestCollectibleGlow.isPairedNpcMarker(411.5, 68.75, 880.5, 411.5, 66.0, 880.5));
    assertTrue(QuestCollectibleGlow.isPairedNpcMarker(0, 2.25, 0, 0, 0, 0));
    assertTrue(QuestCollectibleGlow.isPairedNpcMarker(0, 3.25, 0, 0, 0, 0));
    assertFalse(QuestCollectibleGlow.isPairedNpcMarker(0, 2.24, 0, 0, 0, 0));
    assertFalse(QuestCollectibleGlow.isPairedNpcMarker(0, 3.26, 0, 0, 0, 0));
    assertFalse(QuestCollectibleGlow.isPairedNpcMarker(0.36, 2.75, 0, 0, 0, 0));
  }
}
