package com.chenweikeng.imf.nra.canoe;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * Detects the canoe speed bar in the action-bar {@link Component}.
 *
 * <p>The server sends a styled component shaped like:
 *
 * <pre>
 *   "" (empty parent)
 *     "3.1 "    color=#48DBFB   (cyan, the current speed value)
 *     "["       color=#C8D6E5   (light gray)
 *     "檡檡檡檡" color=#32FF7E   (green, filled bar segments)
 *     "檡檡]"   color=#C8D6E5   (gray, empty bar segments + close bracket)
 * </pre>
 *
 * <p>Each bar segment is {@code U+6AA1} ('檡'), interleaved with invisible {@code U+F001} kerning
 * glyphs from a custom resource-pack font. We only need to recognise "this is a canoe bar", so we
 * extract the leading speed value and a count of segment glyphs (regardless of fill colour) — see
 * {@link Parsed#isCanoeBar()}.
 */
public final class CanoeBarParser {

  /** The bar segment glyph (U+6AA1, '檡'). */
  public static final int SEGMENT_CODEPOINT = 0x6AA1;

  /** Result of parsing the action-bar component. */
  public static final class Parsed {
    /** Parsed speed value (e.g. {@code 3.1f}), or {@code Float.NaN} if not detected. */
    public final float speed;

    /** Total number of bar segments seen. {@code -1} if no bar present. */
    public final int total;

    Parsed(float speed, int total) {
      this.speed = speed;
      this.total = total;
    }

    /** True when this component looks like a canoe speed bar (has both speed and bar). */
    public boolean isCanoeBar() {
      return !Float.isNaN(speed) && total > 0;
    }
  }

  private CanoeBarParser() {}

  /** Parse the given component. Never returns null. */
  public static Parsed parse(Component component) {
    if (component == null) {
      return new Parsed(Float.NaN, -1);
    }

    float[] speedHolder = {Float.NaN};
    int[] totalHolder = {0};

    component.visit(
        (style, text) -> {
          if (text == null || text.isEmpty()) return java.util.Optional.empty();
          // Speed prefix: "3.1 " — purely digits/dot/space, contains a dot
          String trimmed = text.trim();
          if (Float.isNaN(speedHolder[0]) && looksLikeSpeed(trimmed)) {
            try {
              speedHolder[0] = Float.parseFloat(trimmed);
            } catch (NumberFormatException ignored) {
              // not a speed
            }
          }
          totalHolder[0] += countSegments(text);
          return java.util.Optional.empty();
        },
        Style.EMPTY);

    return new Parsed(speedHolder[0], totalHolder[0] == 0 ? -1 : totalHolder[0]);
  }

  private static boolean looksLikeSpeed(String s) {
    if (s.isEmpty()) return false;
    boolean sawDigit = false;
    boolean sawDot = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c >= '0' && c <= '9') sawDigit = true;
      else if (c == '.') {
        if (sawDot) return false;
        sawDot = true;
      } else return false;
    }
    return sawDigit && sawDot;
  }

  private static int countSegments(String text) {
    int n = 0;
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      if (cp == SEGMENT_CODEPOINT) n++;
      i += Character.charCount(cp);
    }
    return n;
  }
}
