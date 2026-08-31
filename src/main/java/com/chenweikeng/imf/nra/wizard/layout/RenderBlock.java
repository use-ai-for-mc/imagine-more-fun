package com.chenweikeng.imf.nra.wizard.layout;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public sealed interface RenderBlock
    permits TextBlock,
        SpacerBlock,
        ImageBlock,
        FloatImageBlock,
        RowBlock,
        ColumnBlock,
        SeparatorBlock {
  int LINE_HEIGHT = 12;
  int TEXT_COLOR = 0xFFFFFFFF;
  int IMAGE_PADDING = 10;

  int getHeight(int containerWidth, Minecraft client);

  void render(GuiGraphicsExtractor graphics, int x, int y, int width, Minecraft client);
}
