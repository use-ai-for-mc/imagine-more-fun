package com.chenweikeng.imf.pim.pin;

public class PinPackTier {
  public final Color color;
  public final int price;

  private PinPackTier(Color color, int price) {
    this.color = color;
    this.price = price;
  }

  public enum Color {
    BLUE,
    PINK,
    GREEN,
    YELLOW,
    UNKNOWN
  }

  public static PinPackTier BLUE = new PinPackTier(Color.BLUE, 400);
  public static PinPackTier PINK = new PinPackTier(Color.PINK, 500);
  public static PinPackTier GREEN = new PinPackTier(Color.GREEN, 100);
  public static PinPackTier YELLOW = new PinPackTier(Color.YELLOW, 400);

  public static PinPackTier fromString(String colorCode) {
    if (colorCode == null || colorCode.isEmpty()) {
      return null;
    }

    String lowerCode = colorCode.toLowerCase();
    if (lowerCode.contains("blue")) {
      return BLUE;
    } else if (lowerCode.contains("light_purple")) {
      return PINK;
    } else if (lowerCode.contains("green")) {
      return GREEN;
    } else if (lowerCode.contains("yellow")) {
      return YELLOW;
    }

    return null;
  }
}
