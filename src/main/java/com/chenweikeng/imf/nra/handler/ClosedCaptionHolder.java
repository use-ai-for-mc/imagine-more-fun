package com.chenweikeng.imf.nra.handler;

import com.chenweikeng.imf.nra.GameState;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.chenweikeng.imf.nra.ride.RideName;
import java.awt.Color;
import java.util.Random;
import net.minecraft.network.chat.Component;

public class ClosedCaptionHolder {
  private static final ClosedCaptionHolder INSTANCE = new ClosedCaptionHolder();
  private static final int DELAY_TICKS = 1;
  private static final int DEFAULT_DISPLAY_TICKS = 100;

  private Component currentCaption = null;
  private Component pendingCaption = null;
  private int delayCounter = 0;
  private int displayTicks = 0;
  private RideName captionRide = null;
  private int colorSeed = 0;
  private String firstAnnouncerName = null;
  private final Random random = new Random();

  private ClosedCaptionHolder() {}

  public static ClosedCaptionHolder getInstance() {
    return INSTANCE;
  }

  public void tick() {
    boolean isRiding = GameState.getInstance().isRiding();

    if (displayTicks > 0 && !isRiding) {
      displayTicks--;
      if (displayTicks == 0) {
        currentCaption = null;
        pendingCaption = null;
      }
    }

    if (delayCounter > 0) {
      delayCounter--;
      if (delayCounter == 0 && pendingCaption != null) {
        currentCaption = pendingCaption;
        pendingCaption = null;
      }
    }
  }

  public void setCaption(Component caption) {
    captionRide = CurrentRideHolder.getCurrentRide();
    if (pendingCaption != null) {
      pendingCaption = caption;
      displayTicks = DEFAULT_DISPLAY_TICKS;
    } else if (currentCaption == null) {
      currentCaption = caption;
      displayTicks = DEFAULT_DISPLAY_TICKS;
    } else {
      currentCaption = null;
      pendingCaption = caption;
      delayCounter = DELAY_TICKS;
      displayTicks = DEFAULT_DISPLAY_TICKS;
    }
  }

  /**
   * Drops a retained caption when the player has moved to a different ride than the one it was
   * shown for. {@link #tick()} only counts the caption down while not riding, so without this a
   * caption lingers onto the next ride when the player switches rides without a gap.
   */
  public void onRideChanged(RideName newRide) {
    if ((currentCaption != null || pendingCaption != null)
        && captionRide != null
        && newRide != null
        && newRide != captionRide) {
      clear();
    }
  }

  public Component getCaption() {
    return currentCaption;
  }

  public boolean shouldDisplay() {
    return displayTicks > 0 && currentCaption != null;
  }

  public void clear() {
    currentCaption = null;
    pendingCaption = null;
    delayCounter = 0;
    displayTicks = 0;
    captionRide = null;
  }

  public void randomizeColorSeed() {
    colorSeed = random.nextInt();
    firstAnnouncerName = null;
  }

  public Color colorFromName(String announcer) {
    if (announcer == null || announcer.isEmpty()) {
      return Color.WHITE;
    }

    if (firstAnnouncerName == null) {
      firstAnnouncerName = announcer;
      return Color.WHITE;
    }

    if (announcer.equals(firstAnnouncerName)) {
      return Color.WHITE;
    }

    int hash = announcer.hashCode() ^ colorSeed;

    float hue = Math.abs(hash % 360);

    if (hue >= 50f && hue <= 70f) {
      hue += 40f;
      if (hue >= 360f) hue -= 360f;
    }

    float saturation = 0.65f + (Math.abs(hash >>> 8) % 20) / 100f;

    float brightness = 0.90f;

    return Color.getHSBColor(hue / 360f, saturation, brightness);
  }
}
