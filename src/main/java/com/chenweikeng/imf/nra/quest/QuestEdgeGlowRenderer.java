package com.chenweikeng.imf.nra.quest;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Purple radar crescent driven by camera-relative quest beam positions. */
public final class QuestEdgeGlowRenderer {
  static final float PULSE_BASE = 0.60F;
  static final float PULSE_AMPLITUDE = 0.15F;
  static final double PULSE_SECONDS = 4.0;
  static final float SOLID_FRACTION = 0.65F;
  static final int TINT_RGB = 0x8A00FF;
  static final int RADAR_LINE_RGB = 0xC45CFF;
  static final float RADAR_LINE_OPACITY = 0.22F;
  static final double[] RADAR_RING_RADII = {0.45, 0.70, 0.95};

  private static long pulseStartedAt;

  private QuestEdgeGlowRenderer() {}

  public static void reset() {
    pulseStartedAt = 0L;
  }

  public static void render(GuiGraphicsExtractor context, DeltaTracker tickCounter) {
    Minecraft client = Minecraft.getInstance();
    double angle = QuestCollectibleGlow.currentGlowAngle();
    if (client.player == null || !Double.isFinite(angle)) {
      reset();
      return;
    }

    long now = System.nanoTime();
    if (pulseStartedAt == 0L) {
      pulseStartedAt = now;
    }
    double elapsedSeconds = (now - pulseStartedAt) * 1.0e-9;
    int alpha = Math.round(pulseOpacity(elapsedSeconds) * 255.0F);
    int width = context.guiWidth();
    int height = context.guiHeight();
    Crescent crescent = crescent(angle, width, height);
    for (int x = 0; x < width; x++) {
      for (Span span : columnSpans(crescent, x + 0.5, height)) {
        int top = Math.max(0, (int) Math.ceil(span.top() - 0.5));
        int bottom = Math.min(height, (int) Math.ceil(span.bottom() - 0.5));
        if (bottom > top) {
          context.fillGradient(
              x,
              top,
              x + 1,
              bottom,
              color(Math.round(alpha * span.topOpacity())),
              color(Math.round(alpha * span.bottomOpacity())));
        }
      }
    }
    drawRadarRings(context, crescent, width, height, alpha);
  }

  /** Two actual circles in GUI pixels; the inner circle shifts away from the target. */
  record Crescent(
      double outerX,
      double outerY,
      double outerRadius,
      double innerX,
      double innerY,
      double innerRadius,
      double feather) {}

  record Span(double top, double bottom, float topOpacity, float bottomOpacity) {}

  static Crescent crescent(double angle, int width, int height) {
    double radians = Math.toRadians(QuestGlowDirection.normalizeAngle(angle));
    double dx = Math.sin(radians);
    double dy = -Math.cos(radians);
    double halfWidth = width * 0.5;
    double halfHeight = height * 0.5;
    double edgeDistance =
        Math.min(
            Math.abs(dx) < 1.0e-10 ? Double.POSITIVE_INFINITY : halfWidth / Math.abs(dx),
            Math.abs(dy) < 1.0e-10 ? Double.POSITIVE_INFINITY : halfHeight / Math.abs(dy));
    double innerRadius = Math.max(width, height) * 1.2;
    double offset = innerRadius - edgeDistance * 0.5;
    return new Crescent(
        halfWidth,
        halfHeight,
        Math.max(width, height) * 1.5,
        halfWidth - dx * offset,
        halfHeight - dy * offset,
        innerRadius,
        edgeDistance * 0.5 * (1.0 - SOLID_FRACTION));
  }

  /** Non-overlapping scanline spans from outer-circle minus inner-circle geometry. */
  static List<Span> columnSpans(Crescent c, double x, int height) {
    List<Span> spans = new ArrayList<>(4);
    double outerDx = x - c.outerX();
    if (Math.abs(outerDx) >= c.outerRadius()) {
      return spans;
    }
    double outerHalf = Math.sqrt(c.outerRadius() * c.outerRadius() - outerDx * outerDx);
    double outerTop = Math.max(0, c.outerY() - outerHalf);
    double outerBottom = Math.min(height, c.outerY() + outerHalf);
    double innerDx = Math.abs(x - c.innerX());
    double featherRadius = c.innerRadius() + c.feather();
    if (innerDx >= featherRadius) {
      addSpan(spans, outerTop, outerBottom, 1, 1, outerTop, outerBottom);
      return spans;
    }
    double featherHalf = Math.sqrt(featherRadius * featherRadius - innerDx * innerDx);
    double innerHalf =
        Math.sqrt(Math.max(0, c.innerRadius() * c.innerRadius() - innerDx * innerDx));
    float minimumOpacity = (float) Math.clamp((innerDx - c.innerRadius()) / c.feather(), 0, 1);
    double fadeTop = c.innerY() - featherHalf;
    double holeTop = c.innerY() - innerHalf;
    double holeBottom = c.innerY() + innerHalf;
    double fadeBottom = c.innerY() + featherHalf;
    addSpan(spans, outerTop, fadeTop, 1, 1, outerTop, outerBottom);
    addSpan(spans, fadeTop, holeTop, 1, minimumOpacity, outerTop, outerBottom);
    addSpan(spans, holeBottom, fadeBottom, minimumOpacity, 1, outerTop, outerBottom);
    addSpan(spans, fadeBottom, outerBottom, 1, 1, outerTop, outerBottom);
    return spans;
  }

  private static void addSpan(
      List<Span> spans,
      double top,
      double bottom,
      float topAlpha,
      float bottomAlpha,
      double clipTop,
      double clipBottom) {
    double start = Math.max(top, clipTop);
    double end = Math.min(bottom, clipBottom);
    if (end <= start) {
      return;
    }
    double slope = (bottomAlpha - topAlpha) / (bottom - top);
    spans.add(
        new Span(
            start,
            end,
            (float) (topAlpha + (start - top) * slope),
            (float) (topAlpha + (end - top) * slope)));
  }

  private static int color(int alpha) {
    return (alpha << 24) | TINT_RGB;
  }

  private static void drawRadarRings(
      GuiGraphicsExtractor context, Crescent crescent, int width, int height, int pulseAlpha) {
    int lineAlpha = Math.round(pulseAlpha * RADAR_LINE_OPACITY);
    double centerX = width * 0.5;
    double centerY = height * 0.5;
    double radiusScale = Math.min(width, height);
    for (double radiusFactor : RADAR_RING_RADII) {
      double radius = radiusScale * radiusFactor;
      for (int x = 0; x < width; x++) {
        double dx = x + 0.5 - centerX;
        if (Math.abs(dx) >= radius) {
          continue;
        }
        double dy = Math.sqrt(radius * radius - dx * dx);
        drawRadarPoint(context, crescent, x, centerY - dy, height, lineAlpha);
        drawRadarPoint(context, crescent, x, centerY + dy, height, lineAlpha);
      }
    }
  }

  private static void drawRadarPoint(
      GuiGraphicsExtractor context, Crescent crescent, int x, double y, int height, int alpha) {
    int pixelY = (int) Math.floor(y);
    float crescentOpacity = opacityAt(crescent, x + 0.5, y, height);
    if (pixelY < 0 || pixelY >= height || crescentOpacity <= 0.0F) {
      return;
    }
    int clippedAlpha = Math.round(alpha * crescentOpacity);
    context.fill(x, pixelY, x + 1, pixelY + 1, (clippedAlpha << 24) | RADAR_LINE_RGB);
  }

  static float opacityAt(Crescent crescent, double x, double y, int height) {
    for (Span span : columnSpans(crescent, x, height)) {
      if (y < span.top() || y > span.bottom()) {
        continue;
      }
      double fraction = (y - span.top()) / (span.bottom() - span.top());
      return (float) (span.topOpacity() + fraction * (span.bottomOpacity() - span.topOpacity()));
    }
    return 0.0F;
  }

  static float pulseOpacity(double elapsedSeconds) {
    return PULSE_BASE
        - PULSE_AMPLITUDE * (float) Math.cos(elapsedSeconds * 2.0 * Math.PI / PULSE_SECONDS);
  }
}
