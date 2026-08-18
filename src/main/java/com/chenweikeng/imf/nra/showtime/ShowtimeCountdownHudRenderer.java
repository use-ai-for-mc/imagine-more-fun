package com.chenweikeng.imf.nra.showtime;

import com.chenweikeng.imf.nra.ServerState;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.showtime.ShowtimeCountdownController.CountdownSnapshot;
import com.chenweikeng.imf.nra.util.TimeFormatUtil;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Draws the active show countdown just left of the crosshair. */
public final class ShowtimeCountdownHudRenderer {
  private static final int CROSSHAIR_GAP = 16;
  private static final int PADDING = 4;
  private static final int LINE_HEIGHT = 9;
  private static final int LABEL_COLOR = 0xFFFFFFFF;
  private static final int COUNTDOWN_COLOR = 0xFF32FF7E;
  private static final int STARTING_COLOR = 0xFFFFD75A;

  private ShowtimeCountdownHudRenderer() {}

  public static void render(GuiGraphics context, DeltaTracker tickCounter) {
    if (!ServerState.isImagineFunServer()) {
      return;
    }

    Minecraft client = Minecraft.getInstance();
    if (client.player == null) {
      return;
    }

    CountdownSnapshot snapshot =
        ShowtimeCountdownController.getInstance().getSnapshot(System.currentTimeMillis());
    if (snapshot == null) {
      return;
    }

    String label = snapshot.attractionName() + " — ";
    String value =
        snapshot.startingNow()
            ? "Starting now!"
            : TimeFormatUtil.formatDuration(snapshot.remainingSeconds());
    int valueColor = snapshot.startingNow() ? STARTING_COLOR : COUNTDOWN_COLOR;

    int textWidth = client.font.width(label) + client.font.width(value);
    int boxWidth = textWidth + PADDING * 2;
    int boxHeight = LINE_HEIGHT + PADDING * 2;
    int boxX = context.guiWidth() / 2 - boxWidth - CROSSHAIR_GAP;
    int boxY = (context.guiHeight() - boxHeight) / 2;

    int opacity = ModConfig.currentSetting.hudBackgroundOpacity;
    if (opacity > 0) {
      int alpha = (int) (opacity * 2.55);
      context.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, alpha << 24);
    }

    int textX = boxX + PADDING;
    int textY = boxY + PADDING;
    context.drawString(client.font, label, textX, textY, LABEL_COLOR, true);
    context.drawString(
        client.font, value, textX + client.font.width(label), textY, valueColor, true);
  }
}
