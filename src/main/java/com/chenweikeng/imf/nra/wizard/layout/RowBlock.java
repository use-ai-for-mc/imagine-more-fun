package com.chenweikeng.imf.nra.wizard.layout;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public record RowBlock(List<RenderBlock> columns, VerticalAlignment verticalAlignment)
    implements RenderBlock {

  public static final int COLUMN_GAP = 5;

  public RowBlock(List<RenderBlock> columns) {
    this(columns, VerticalAlignment.TOP);
  }

  @Override
  public int getHeight(int containerWidth, Minecraft client) {
    int columnWidth = calculateColumnWidth(containerWidth);
    int maxHeight = 0;
    for (RenderBlock column : columns) {
      maxHeight = Math.max(maxHeight, column.getHeight(columnWidth, client));
    }
    return maxHeight;
  }

  private int calculateColumnWidth(int containerWidth) {
    int totalGapWidth = (columns.size() - 1) * COLUMN_GAP;
    return (containerWidth - totalGapWidth) / columns.size();
  }

  @Override
  public void render(GuiGraphicsExtractor graphics, int x, int y, int width, Minecraft client) {
    int columnWidth = calculateColumnWidth(width);
    int maxHeight = getHeight(width, client);
    int columnX = x;

    for (RenderBlock column : columns) {
      int columnHeight = column.getHeight(columnWidth, client);
      int columnY = y;

      if (verticalAlignment == VerticalAlignment.CENTER) {
        columnY = y + (maxHeight - columnHeight) / 2;
      }

      column.render(graphics, columnX, columnY, columnWidth, client);
      columnX += columnWidth + COLUMN_GAP;
    }
  }
}
