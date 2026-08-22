package com.chenweikeng.imf.nra.wizard.layout;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public record SpacerBlock(int height) implements RenderBlock {

  @Override
  public int getHeight(int containerWidth, Minecraft client) {
    return height;
  }

  @Override
  public void render(GuiGraphicsExtractor graphics, int x, int y, int width, Minecraft client) {}
}
