package com.chenweikeng.imf.nra.wizard.layout;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

public record FloatImageBlock(
    Identifier texture, double widthPercent, boolean floatLeft, Component text)
    implements RenderBlock {

  private static final int GAP = 10;

  @Override
  public int getHeight(int containerWidth, Minecraft client) {
    int imgWidth = (int) (containerWidth * widthPercent);
    int textWidth = containerWidth - imgWidth - GAP;
    int imgHeight = 100;
    List<FormattedCharSequence> lines = client.font.split(text, textWidth);
    int textHeight = lines.size() * LINE_HEIGHT;
    return Math.max(imgHeight, textHeight);
  }

  @Override
  public void render(GuiGraphicsExtractor graphics, int x, int y, int width, Minecraft client) {
    int imgWidth = (int) (width * widthPercent);
    int imgHeight = 100;
    int textWidth = width - imgWidth - GAP;
    int imgX = floatLeft ? x : x + width - imgWidth;
    int textX = floatLeft ? x + imgWidth + GAP : x;

    graphics.blit(texture, imgX, y, imgX + imgWidth, y + imgHeight, 0f, 1f, 0f, 1f);

    List<FormattedCharSequence> lines = client.font.split(text, textWidth);
    int lineY = y;
    for (FormattedCharSequence line : lines) {
      graphics.text(client.font, line, textX, lineY, TEXT_COLOR, false);
      lineY += LINE_HEIGHT;
    }
  }
}
