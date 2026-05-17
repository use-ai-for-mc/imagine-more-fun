package com.chenweikeng.imf.nra.config.profile.ui;

import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.config.profile.ProfileManager;
import com.chenweikeng.imf.nra.config.profile.StoredProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;

public class CurrentSettingsEntry {
  private static final int HEIGHT = 36;
  private static final int BUTTON_SPACING = 4;
  private static final int BG_COLOR = 0xFF222244;
  private static final int NAME_COLOR = 0xFFFFFFFF;
  private static final int DESCRIPTION_COLOR = 0xFFAAAAAA;
  private static final int ACTIVE_INDICATOR_COLOR = 0xFF5555FF;

  private final Minecraft minecraft;
  private final Runnable onEdit;
  private final Runnable onSaveTo;
  private final int x;
  private final int y;
  private final int width;
  private final int editButtonX;
  private final int editButtonWidth;
  private final int editButtonY;
  private final int saveButtonX;
  private final int saveButtonWidth;
  private final int saveButtonY;

  public CurrentSettingsEntry(
      Minecraft minecraft, int x, int y, int width, Runnable onEdit, Runnable onSaveTo) {
    this.minecraft = minecraft;
    this.x = x;
    this.y = y;
    this.width = width;
    this.onEdit = onEdit;
    this.onSaveTo = onSaveTo;

    editButtonWidth = 40;
    saveButtonWidth = 60;
    int totalButtonWidth = editButtonWidth + BUTTON_SPACING + saveButtonWidth;
    int buttonY = y + (HEIGHT - ButtonRenderer.BUTTON_HEIGHT) / 2;
    int buttonStartX = x + width - totalButtonWidth - 4;

    editButtonX = buttonStartX;
    editButtonY = buttonY;
    saveButtonX = buttonStartX + editButtonWidth + BUTTON_SPACING;
    saveButtonY = buttonY;
  }

  public void addWidgets(WidgetConsumer widgetConsumer) {}

  public void render(GuiGraphics graphics, int mouseX, int mouseY) {
    graphics.fill(x, y, x + width, y + HEIGHT, BG_COLOR);

    int textX = x + 4;
    int textY = y + 2;

    String indicator = "\u25CF";
    graphics.drawString(minecraft.font, indicator, textX, textY, ACTIVE_INDICATOR_COLOR, false);

    textX += 14;
    graphics.drawString(minecraft.font, "Current Settings", textX, textY, NAME_COLOR, false);

    StoredProfile matchingProfile =
        ProfileManager.getAllProfiles().stream()
            .filter(p -> p.data.equals(ModConfig.currentSetting))
            .findFirst()
            .orElse(null);

    if (matchingProfile != null) {
      graphics.drawString(
          minecraft.font, "Same as a saved profile", textX, textY + 12, DESCRIPTION_COLOR, false);
    } else {
      graphics.drawString(
          minecraft.font,
          "Not linked to any saved profile",
          textX,
          textY + 12,
          DESCRIPTION_COLOR,
          false);
    }

    ButtonRenderer.renderButton(
        minecraft,
        graphics,
        mouseX,
        mouseY,
        editButtonX,
        editButtonY,
        editButtonWidth,
        "Edit",
        ButtonRenderer.STYLE_EDIT);
    ButtonRenderer.renderButton(
        minecraft,
        graphics,
        mouseX,
        mouseY,
        saveButtonX,
        saveButtonY,
        saveButtonWidth,
        "Save to...",
        ButtonRenderer.STYLE_SAVE);
  }

  public boolean mouseClicked(MouseButtonEvent event) {
    if (event.button() != 0) {
      return false;
    }

    int mouseX = (int) event.x();
    int mouseY = (int) event.y();

    if (ButtonRenderer.isMouseOver(mouseX, mouseY, editButtonX, editButtonY, editButtonWidth)) {
      onEdit.run();
      return true;
    }
    if (ButtonRenderer.isMouseOver(mouseX, mouseY, saveButtonX, saveButtonY, saveButtonWidth)) {
      onSaveTo.run();
      return true;
    }

    return false;
  }

  public interface WidgetConsumer {
    void accept();
  }
}
