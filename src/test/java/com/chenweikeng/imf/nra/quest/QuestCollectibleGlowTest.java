package com.chenweikeng.imf.nra.quest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.particles.DustParticleOptions;
import org.junit.jupiter.api.Test;

class QuestCollectibleGlowTest {
  @Test
  void matchesOnlyPureWhiteUnitScaleDust() {
    assertTrue(QuestCollectibleGlow.isMatchingDust(new DustParticleOptions(0xFFFFFF, 1.0F)));
    assertFalse(QuestCollectibleGlow.isMatchingDust(new DustParticleOptions(0xFFFFFE, 1.0F)));
    assertFalse(QuestCollectibleGlow.isMatchingDust(new DustParticleOptions(0xFFFFFF, 2.0F)));
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
}
