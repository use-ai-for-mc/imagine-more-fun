package com.chenweikeng.imf.nra.wizard.layout;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public record SeparatorBlock(int height) implements RenderBlock {

  private static final int LINE_COLOR = 0x44FFFFFF;
  private static final int LINE_PADDING = 40;

  @Override
  public int getHeight(int containerWidth, Minecraft client) {
    return height;
  }

  @Override
  public void render(GuiGraphicsExtractor graphics, int x, int y, int width, Minecraft client) {
    int lineY = y + height / 2;
    graphics.fill(x + LINE_PADDING, lineY, x + width - LINE_PADDING, lineY + 1, LINE_COLOR);
  }
}
