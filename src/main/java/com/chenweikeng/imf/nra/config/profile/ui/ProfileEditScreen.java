package com.chenweikeng.imf.nra.config.profile.ui;

import com.chenweikeng.imf.nra.config.profile.ProfileManager;
import com.chenweikeng.imf.nra.config.profile.StoredProfile;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class ProfileEditScreen extends Screen {
  private static final int FIELD_WIDTH = 280;
  private static final int FIELD_HEIGHT = 20;
  private static final int BUTTON_WIDTH = 80;
  private static final int BUTTON_HEIGHT = 20;
  private static final int LABEL_COLOR = 0xFFFFFFFF;
  private static final int ERROR_COLOR = 0xFFFF5555;

  private final Screen parent;
  private final StoredProfile existingProfile;
  private final Consumer<StoredProfile> onSave;
  private final boolean isRenameMode;

  private EditBox nameBox;
  private EditBox descriptionBox;
  private Button saveButton;
  private Button cancelButton;

  private String errorMessage = null;

  private ProfileEditScreen(
      Screen parent, StoredProfile existingProfile, Consumer<StoredProfile> onSave) {
    super(Component.literal(existingProfile != null ? "Rename Profile" : "Create Profile"));
    this.parent = parent;
    this.existingProfile = existingProfile;
    this.onSave = onSave;
    this.isRenameMode = existingProfile != null;
  }

  public static ProfileEditScreen createNew(Screen parent, Consumer<StoredProfile> onSave) {
    return new ProfileEditScreen(parent, null, onSave);
  }

  public static ProfileEditScreen createRename(
      Screen parent, StoredProfile profile, Consumer<StoredProfile> onSave) {
    return new ProfileEditScreen(parent, profile, onSave);
  }

  @Override
  protected void init() {
    super.init();

    int centerX = width / 2;
    int startY = height / 2 - 60;

    int labelY = startY;
    int fieldY = labelY + 12;

    nameBox =
        new EditBox(
            font,
            centerX - FIELD_WIDTH / 2,
            fieldY,
            FIELD_WIDTH,
            FIELD_HEIGHT,
            Component.literal("Profile Name"));
    nameBox.setMaxLength(64);
    nameBox.setCanLoseFocus(true);
    if (isRenameMode) {
      nameBox.setValue(existingProfile.name);
    }
    addRenderableWidget(nameBox);

    int descLabelY = fieldY + FIELD_HEIGHT + 16;
    int descFieldY = descLabelY + 12;

    descriptionBox =
        new EditBox(
            font,
            centerX - FIELD_WIDTH / 2,
            descFieldY,
            FIELD_WIDTH,
            FIELD_HEIGHT,
            Component.literal("Description"));
    descriptionBox.setMaxLength(128);
    descriptionBox.setCanLoseFocus(true);
    if (isRenameMode && existingProfile.description != null) {
      descriptionBox.setValue(existingProfile.description);
    }
    addRenderableWidget(descriptionBox);

    int buttonY = descFieldY + FIELD_HEIGHT + 30;
    int buttonSpacing = 10;
    int totalButtonWidth = BUTTON_WIDTH * 2 + buttonSpacing;
    int buttonStartX = centerX - totalButtonWidth / 2;

    String saveText = isRenameMode ? "Save" : "Create";
    saveButton =
        Button.builder(Component.literal(saveText), this::onSaveClicked)
            .bounds(buttonStartX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT)
            .build();
    addRenderableWidget(saveButton);

    cancelButton =
        Button.builder(Component.literal("Cancel"), this::onCancelClicked)
            .bounds(
                buttonStartX + BUTTON_WIDTH + buttonSpacing, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT)
            .build();
    addRenderableWidget(cancelButton);

    setInitialFocus(nameBox);
  }

  private void onSaveClicked(Button button) {
    String name = nameBox.getValue().trim();
    String description = descriptionBox.getValue().trim();

    if (name.isEmpty()) {
      errorMessage = "Profile name cannot be empty";
      return;
    }

    String excludeId = isRenameMode ? existingProfile.id : null;
    if (!ProfileManager.isNameUnique(name, excludeId)) {
      errorMessage = "A profile with this name already exists";
      return;
    }

    errorMessage = null;

    StoredProfile savedProfile;
    if (isRenameMode) {
      ProfileManager.renameProfile(
          existingProfile.id, name, description.isEmpty() ? null : description);
      savedProfile = ProfileManager.getProfile(existingProfile.id);
    } else {
      savedProfile =
          ProfileManager.saveCurrentAsProfile(name, description.isEmpty() ? null : description);
    }

    if (onSave != null) {
      onSave.accept(savedProfile);
    }

    onClose();
  }

  private void onCancelClicked(Button button) {
    onClose();
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
    renderDarkBackground(graphics);

    int centerX = width / 2;
    int startY = height / 2 - 60;

    graphics.centeredText(font, this.title, centerX, startY - 25, LABEL_COLOR);

    int labelY = startY;
    int fieldY = labelY + 12;

    graphics.text(font, "Profile Name:", centerX - FIELD_WIDTH / 2, labelY, LABEL_COLOR, false);

    if (nameBox.getValue().isEmpty() && !nameBox.isFocused()) {
      nameBox.setSuggestion("Enter profile name...");
    } else {
      nameBox.setSuggestion(null);
    }

    int descLabelY = fieldY + FIELD_HEIGHT + 16;
    int descFieldY = descLabelY + 12;

    graphics.text(
        font, "Description (optional):", centerX - FIELD_WIDTH / 2, descLabelY, LABEL_COLOR, false);

    if (descriptionBox.getValue().isEmpty() && !descriptionBox.isFocused()) {
      descriptionBox.setSuggestion("Enter description...");
    } else {
      descriptionBox.setSuggestion(null);
    }

    super.extractRenderState(graphics, mouseX, mouseY, delta);

    if (errorMessage != null) {
      int errorY = descFieldY + FIELD_HEIGHT + 8;
      graphics.centeredText(font, errorMessage, centerX, errorY, ERROR_COLOR);
    }
  }

  private void renderDarkBackground(GuiGraphicsExtractor graphics) {
    graphics.fill(0, 0, this.width, this.height, 0xCC000000);
  }

  @Override
  public boolean shouldCloseOnEsc() {
    return true;
  }

  @Override
  public void onClose() {
    if (minecraft != null && minecraft.gui.screen() == this) {
      minecraft.setScreenAndShow(parent);
    }
  }
}
