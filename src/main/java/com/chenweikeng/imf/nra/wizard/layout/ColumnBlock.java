package com.chenweikeng.imf.nra.wizard.layout;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public record ColumnBlock(List<RenderBlock> blocks) implements RenderBlock {

  @Override
  public int getHeight(int containerWidth, Minecraft client) {
    int totalHeight = 0;
    for (RenderBlock block : blocks) {
      totalHeight += block.getHeight(containerWidth, client);
    }
    return totalHeight;
  }

  @Override
  public void render(GuiGraphicsExtractor graphics, int x, int y, int width, Minecraft client) {
    int currentY = y;
    for (RenderBlock block : blocks) {
      int blockHeight = block.getHeight(width, client);
      block.render(graphics, x, currentY, width, client);
      currentY += blockHeight;
    }
  }
}
