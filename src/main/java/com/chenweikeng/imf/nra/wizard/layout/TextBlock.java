package com.chenweikeng.imf.nra.wizard.layout;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public record TextBlock(Component text) implements RenderBlock {

  @Override
  public int getHeight(int containerWidth, Minecraft client) {
    List<FormattedCharSequence> lines = client.font.split(text, containerWidth);
    return lines.size() * LINE_HEIGHT;
  }

  @Override
  public void render(GuiGraphicsExtractor graphics, int x, int y, int width, Minecraft client) {
    List<FormattedCharSequence> lines = client.font.split(text, width);
    int lineY = y;
    for (FormattedCharSequence line : lines) {
      graphics.text(client.font, line, x, lineY, TEXT_COLOR, false);
      lineY += LINE_HEIGHT;
    }
  }
}
