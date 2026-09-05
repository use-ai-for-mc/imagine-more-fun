package com.chenweikeng.imf.nra.quest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class QuestGlowDirectionTest {
  @Test
  void matchesPimSymbolOrderClockwiseFromForward() {
    assertArrayEquals(
        new String[] {"⬆", "↗", "➡", "↘", "⬇", "↙", "⬅", "↖"}, QuestGlowDirection.SYMBOLS);
    assertEquals(QuestGlowDirection.UP, QuestGlowDirection.fromSymbol("⬆"));
    assertEquals(QuestGlowDirection.UP_RIGHT, QuestGlowDirection.fromSymbol("↗"));
    assertEquals(QuestGlowDirection.RIGHT, QuestGlowDirection.fromSymbol("➡"));
    assertEquals(QuestGlowDirection.DOWN_RIGHT, QuestGlowDirection.fromSymbol("↘"));
    assertEquals(QuestGlowDirection.DOWN, QuestGlowDirection.fromSymbol("⬇"));
    assertEquals(QuestGlowDirection.DOWN_LEFT, QuestGlowDirection.fromSymbol("↙"));
    assertEquals(QuestGlowDirection.LEFT, QuestGlowDirection.fromSymbol("⬅"));
    assertEquals(QuestGlowDirection.UP_LEFT, QuestGlowDirection.fromSymbol("↖"));
  }

  @Test
  void parsesLiveQuestTitlesAndIgnoresMissingOrEmbeddedArrows() {
    assertEquals(
        QuestGlowDirection.UP,
        QuestGlowDirection.fromBossBarTitle("Quest: Meditate with Yoda (4.7) ⬆"));
    assertEquals(
        QuestGlowDirection.LEFT,
        QuestGlowDirection.fromBossBarTitle("Quest: Find 5 Honey Pots (1357.5) ⬅"));
    assertEquals(
        QuestGlowDirection.DOWN,
        QuestGlowDirection.fromBossBarTitle("Quest: Find 5 Honey Pots (20.9) ⬇"));
    assertEquals(
        QuestGlowDirection.NONE,
        QuestGlowDirection.fromBossBarTitle("Quest: Find 5 Honey Pots (20.9)"));
    assertEquals(QuestGlowDirection.NONE, QuestGlowDirection.fromBossBarTitle(null));
    assertEquals(QuestGlowDirection.NONE, QuestGlowDirection.fromBossBarTitle(""));
    assertEquals(
        QuestGlowDirection.NONE,
        QuestGlowDirection.fromBossBarTitle("Quest: ⬅ Find 5 Honey Pots (20.9)"));
  }

  @Test
  void acceptsStraightArrowAliases() {
    assertEquals(QuestGlowDirection.UP, QuestGlowDirection.fromSymbol("↑"));
    assertEquals(QuestGlowDirection.RIGHT, QuestGlowDirection.fromSymbol("→"));
    assertEquals(QuestGlowDirection.DOWN, QuestGlowDirection.fromSymbol("↓"));
    assertEquals(QuestGlowDirection.LEFT, QuestGlowDirection.fromSymbol("←"));
  }

  @Test
  void drawsEveryParsedArrowIncludingForward() {
    assertTrue(QuestGlowDirection.UP.showsEdgeGlow());
    assertFalse(QuestGlowDirection.NONE.showsEdgeGlow());
    assertTrue(QuestGlowDirection.LEFT.showsEdgeGlow());
    assertTrue(QuestGlowDirection.DOWN_RIGHT.showsEdgeGlow());
  }

  @Test
  void rectangleUsesBossBarDistanceWithInclusive300BlockLimitButNeedsNoArrow() {
    for (String distance : new String[] {"0", "20.9", "299.9", "300", "300.0"}) {
      assertTrue(
          QuestCollectibleGlow.isEdgeGlowWithinRange(
              "Quest: Find 500 Honey Pots (" + distance + ")"));
    }
    for (String distance : new String[] {"300.1", "301", "1357.5"}) {
      assertFalse(
          QuestCollectibleGlow.isEdgeGlowWithinRange(
              "Quest: Find 5 Honey Pots (" + distance + ") ⬆"));
    }
    assertFalse(QuestCollectibleGlow.isEdgeGlowWithinRange(null));
    assertFalse(QuestCollectibleGlow.isEdgeGlowWithinRange("Quest: Find 5 Honey Pots (2/5) ⬆"));
  }

  @Test
  void computesAllEightDirectionsFromBeamPositions() {
    Vec3[] targets = {
      new Vec3(0, 50, 10), new Vec3(-10, 50, 10), new Vec3(-10, 50, 0),
      new Vec3(-10, 50, -10), new Vec3(0, 50, -10), new Vec3(10, 50, -10),
      new Vec3(10, 50, 0), new Vec3(10, 50, 10)
    };
    for (int i = 0; i < targets.length; i++) {
      assertEquals(
          i * 45.0, QuestGlowDirection.angleToNearestBeam(List.of(targets[i]), Vec3.ZERO, 0));
    }
  }

  @Test
  void turningAndMovingUpdateDirectionWithoutServerChanges() {
    List<Vec3> beams = List.of(new Vec3(0, 100, 10));
    assertEquals(0.0, QuestGlowDirection.angleToNearestBeam(beams, Vec3.ZERO, 0));
    assertEquals(270.0, QuestGlowDirection.angleToNearestBeam(beams, Vec3.ZERO, 90));
    assertEquals(90.0, QuestGlowDirection.angleToNearestBeam(beams, Vec3.ZERO, -90));
    assertEquals(0.0, QuestGlowDirection.angleToNearestBeam(beams, Vec3.ZERO, 720));
    assertEquals(180.0, QuestGlowDirection.angleToNearestBeam(beams, new Vec3(0, 0, 20), 0));
  }

  @Test
  void selectsNearestHorizontalBeamAndHidesWhenTargetsDisappear() {
    Vec3 near = new Vec3(-2, 100, 0);
    Vec3 far = new Vec3(0, 0, 10);
    assertEquals(90.0, QuestGlowDirection.angleToNearestBeam(List.of(far, near), Vec3.ZERO, 0));
    assertEquals(Double.NaN, QuestGlowDirection.angleToNearestBeam(List.of(), Vec3.ZERO, 0));
    assertEquals(
        0.0, QuestGlowDirection.angleToNearestBeam(List.of(new Vec3(0, 100, 0)), Vec3.ZERO, 90));
    assertEquals(
        QuestGlowDirection.angleToNearestBeam(List.of(near, new Vec3(2, 0, 0)), Vec3.ZERO, 0),
        QuestGlowDirection.angleToNearestBeam(List.of(new Vec3(2, 0, 0), near), Vec3.ZERO, 0));
  }

  @Test
  void preservesSubSectorAnglesAndMovesTheRegionContinuously() {
    List<Vec3> beams = List.of(new Vec3(0, 0, 10));
    assertEquals(12.3, QuestGlowDirection.angleToNearestBeam(beams, Vec3.ZERO, -12.3F), 0.00001);
  }

  @Test
  void crescentKeepsTheCenterClearAndLightsTheTargetEdgeAtEveryAngle() {
    for (int[] size : new int[][] {{1000, 600}, {600, 1000}, {800, 800}, {1600, 450}}) {
      int width = size[0];
      int height = size[1];
      for (int angle = 0; angle < 360; angle++) {
        var c = QuestEdgeGlowRenderer.crescent(angle, width, height);
        assertTrue(c.outerRadius() > Math.hypot(width / 2.0, height / 2.0));
        assertTrue(c.outerRadius() > c.innerRadius());
        assertEquals(
            0.0F, QuestEdgeGlowRenderer.opacityAt(c, width / 2.0, height / 2.0, height), 0.001F);
        double dx = Math.sin(Math.toRadians(angle));
        double dy = -Math.cos(Math.toRadians(angle));
        double reach =
            Math.min(
                Math.abs(dx) < 1e-10 ? Double.POSITIVE_INFINITY : width / 2.0 / Math.abs(dx),
                Math.abs(dy) < 1e-10 ? Double.POSITIVE_INFINITY : height / 2.0 / Math.abs(dy));
        assertTrue(
            QuestEdgeGlowRenderer.opacityAt(
                    c, width / 2.0 + dx * reach * 0.98, height / 2.0 + dy * reach * 0.98, height)
                > 0.95F);
        assertEquals(
            0.0F,
            QuestEdgeGlowRenderer.opacityAt(
                c, width / 2.0 - dx * reach * 0.98, height / 2.0 - dy * reach * 0.98, height),
            0.001F);
      }
    }
  }

  @Test
  void circleSpansAreClippedNonOverlappingAndHaveCurvedInnerBoundary() {
    var c = QuestEdgeGlowRenderer.crescent(0, 1000, 600);
    var center = QuestEdgeGlowRenderer.columnSpans(c, 500, 600);
    var side = QuestEdgeGlowRenderer.columnSpans(c, 100, 600);
    assertEquals(150, center.getLast().bottom(), 0.001);
    assertTrue(side.getLast().bottom() > center.getLast().bottom() + 50);
    assertEquals(
        QuestEdgeGlowRenderer.crescent(0, 1000, 600),
        QuestEdgeGlowRenderer.crescent(360, 1000, 600));
    for (int angle = 0; angle < 360; angle += 7) {
      c = QuestEdgeGlowRenderer.crescent(angle, 1000, 600);
      for (int x = 0; x < 1000; x += 13) {
        double previousEnd = 0;
        for (var span : QuestEdgeGlowRenderer.columnSpans(c, x + 0.5, 600)) {
          assertTrue(span.top() >= previousEnd && span.bottom() <= 600);
          assertTrue(span.bottom() > span.top());
          assertTrue(span.topOpacity() >= 0 && span.topOpacity() <= 1);
          assertTrue(span.bottomOpacity() >= 0 && span.bottomOpacity() <= 1);
          previousEnd = span.bottom();
        }
      }
    }
  }

  @Test
  void pulseRemainsVisibleThroughoutItsSlowCycle() {
    assertEquals(0.45F, QuestEdgeGlowRenderer.pulseOpacity(0.0), 0.0001F);
    assertEquals(0.75F, QuestEdgeGlowRenderer.pulseOpacity(2.0), 0.0001F);
    assertEquals(0.45F, QuestEdgeGlowRenderer.pulseOpacity(4.0), 0.0001F);
    for (int frame = 0; frame <= 240; frame++) {
      float opacity = QuestEdgeGlowRenderer.pulseOpacity(frame / 60.0);
      assertTrue(opacity >= 0.4499F && opacity <= 0.7501F);
    }
  }
}
