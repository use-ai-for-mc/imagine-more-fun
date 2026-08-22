package com.chenweikeng.imf.nra.config.profile.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class ButtonRenderer {
  public static final int BUTTON_HEIGHT = 14;
  private static final int BUTTON_BORDER_COLOR = 0xFFA0A0A0;
  private static final int BUTTON_TEXT_COLOR = 0xFF202020;

  public static final class ButtonStyle {
    public final int bgColor;
    public final int bgHoverColor;

    public ButtonStyle(int bgColor, int bgHoverColor) {
      this.bgColor = bgColor;
      this.bgHoverColor = bgHoverColor;
    }

    public static ButtonStyle apply(int baseColor, int hoverColor) {
      return new ButtonStyle(baseColor, hoverColor);
    }
  }

  public static final ButtonStyle STYLE_APPLY = ButtonStyle.apply(0xFF4CAF50, 0xFF66BB6A);

  public static final ButtonStyle STYLE_RENAME = ButtonStyle.apply(0xFFFFA726, 0xFFFFB74D);

  public static final ButtonStyle STYLE_EDIT = ButtonStyle.apply(0xFF42A5F5, 0xFF64B5F6);

  public static final ButtonStyle STYLE_DELETE = ButtonStyle.apply(0xFFEF5350, 0xFFEF5350);

  public static final ButtonStyle STYLE_SAVE = ButtonStyle.apply(0xFFAB47BC, 0xFFBA68C8);

  public static final ButtonStyle STYLE_VIEW = ButtonStyle.apply(0xFF78909C, 0xFF90A4AE);

  private ButtonRenderer() {}

  public static void renderButton(
      Minecraft minecraft,
      GuiGraphicsExtractor graphics,
      int mouseX,
      int mouseY,
      int buttonX,
      int buttonY,
      int width,
      String text,
      ButtonStyle style) {
    boolean isHovered =
        mouseX >= buttonX
            && mouseX < buttonX + width
            && mouseY >= buttonY
            && mouseY < buttonY + BUTTON_HEIGHT;

    int bgColor = isHovered ? style.bgHoverColor : style.bgColor;

    graphics.fill(buttonX, buttonY, buttonX + width, buttonY + BUTTON_HEIGHT, bgColor);
    graphics.fill(buttonX, buttonY, buttonX + 1, buttonY + BUTTON_HEIGHT, BUTTON_BORDER_COLOR);
    graphics.fill(buttonX, buttonY, buttonX + width, buttonY + 1, BUTTON_BORDER_COLOR);
    graphics.fill(
        buttonX + width - 1,
        buttonY,
        buttonX + width,
        buttonY + BUTTON_HEIGHT,
        BUTTON_BORDER_COLOR);
    graphics.fill(
        buttonX,
        buttonY + BUTTON_HEIGHT - 1,
        buttonX + width,
        buttonY + BUTTON_HEIGHT,
        BUTTON_BORDER_COLOR);

    int textWidth = minecraft.font.width(text);
    graphics.text(
        minecraft.font,
        text,
        buttonX + (width - textWidth) / 2,
        buttonY + (BUTTON_HEIGHT - 9) / 2 + 1,
        BUTTON_TEXT_COLOR,
        false);
  }

  public static boolean isMouseOver(int mouseX, int mouseY, int buttonX, int buttonY, int width) {
    return mouseX >= buttonX
        && mouseX < buttonX + width
        && mouseY >= buttonY
        && mouseY < buttonY + BUTTON_HEIGHT;
  }
}
