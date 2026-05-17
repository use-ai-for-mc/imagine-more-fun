package com.chenweikeng.imf.nra.wizard.layout;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

public record ImageBlock(Identifier texture, int imgWidth, int imgHeight) implements RenderBlock {

  @Override
  public int getHeight(int containerWidth, Minecraft client) {
    double scale = calculateScaleFactor(containerWidth);
    int scaledHeight = (int) (imgHeight * scale);
    return scaledHeight + IMAGE_PADDING;
  }

  @Override
  public void render(GuiGraphics graphics, int x, int y, int width, Minecraft client) {
    float scale = (float) calculateScaleFactor(width);
    int scaledWidth = (int) (imgWidth * scale);
    int scaledHeight = (int) (imgHeight * scale);
    int imgX = x + (width - scaledWidth) / 2;
    graphics.blit(
        texture,
        imgX,
        y,
        imgX + scaledWidth,
        y + scaledHeight,
        0f,
        1f,
        0f,
        1f); // 0f, 1f, 0f, 1f spans the full UV range — draws the whole image.
  }

  private double calculateScaleFactor(int containerWidth) {
    if (containerWidth < imgWidth) {
      return (double) containerWidth / imgWidth;
    }
    return 1.0;
  }
}
