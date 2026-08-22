package com.chenweikeng.imf.nra.config.profile.ui;

import com.chenweikeng.imf.nra.config.profile.BuiltInProfiles;
import com.chenweikeng.imf.nra.config.profile.ProfileManager;
import com.chenweikeng.imf.nra.config.profile.StoredProfile;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class ProfileListWidget extends ObjectSelectionList<ProfileListWidget.Entry> {
  private static final int ENTRY_HEIGHT = 36;
  private static final int BADGE_ACTIVE_COLOR = 0xFF55FF55;
  private static final int NAME_COLOR = 0xFFFFFFFF;
  private static final int DESCRIPTION_COLOR = 0xFFAAAAAA;
  private static final int BUILTIN_TAG_COLOR = 0xFF00AA00;
  private static final int SELECTED_BG_COLOR = 0x55FFFFFF;
  private static final int HOVER_BG_COLOR = 0x33FFFFFF;

  private final Consumer<StoredProfile> onProfileApply;
  private final Consumer<StoredProfile> onProfileRename;
  private final Consumer<StoredProfile> onProfileEdit;
  private final Consumer<StoredProfile> onProfileDelete;
  private final Consumer<StoredProfile> onSelectionChange;

  public ProfileListWidget(
      Minecraft minecraft,
      int width,
      int height,
      int y,
      Consumer<StoredProfile> onProfileApply,
      Consumer<StoredProfile> onProfileRename,
      Consumer<StoredProfile> onProfileEdit,
      Consumer<StoredProfile> onProfileDelete,
      Consumer<StoredProfile> onSelectionChange) {
    super(minecraft, width, height, y, ENTRY_HEIGHT);
    this.onProfileApply = onProfileApply;
    this.onProfileRename = onProfileRename;
    this.onProfileEdit = onProfileEdit;
    this.onProfileDelete = onProfileDelete;
    this.onSelectionChange = onSelectionChange;
  }

  public void setProfiles(List<StoredProfile> profiles) {
    clearEntries();
    for (StoredProfile profile : profiles) {
      addEntry(new Entry(profile));
    }
  }

  public StoredProfile getSelectedProfile() {
    Entry selected = getSelected();
    return selected != null ? selected.profile : null;
  }

  public void selectProfile(String profileId) {
    for (int i = 0; i < getItemCount(); i++) {
      Entry entry = children().get(i);
      if (entry.profile.id.equals(profileId)) {
        setSelected(entry);
        scrollToEntry(entry);
        return;
      }
    }
  }

  public void refreshProfiles() {
    String selectedId = getSelectedProfile() != null ? getSelectedProfile().id : null;
    setProfiles(ProfileManager.getAllProfiles());
    if (selectedId != null) {
      selectProfile(selectedId);
    }
  }

  @Override
  public int getRowWidth() {
    return width - 40;
  }

  @Override
  public void setSelected(Entry entry) {
    super.setSelected(entry);
    if (entry != null && onSelectionChange != null) {
      onSelectionChange.accept(entry.profile);
    }
  }

  public class Entry extends ObjectSelectionList.Entry<Entry> {
    private final StoredProfile profile;
    private long lastClickTime;
    private static final int DOUBLE_CLICK_THRESHOLD = 500;
    private static final int BUTTON_WIDTH = 40;
    private static final int BUTTON_SPACING = 4;

    public Entry(StoredProfile profile) {
      this.profile = profile;
    }

    private boolean isBuiltInProfile() {
      for (StoredProfile builtIn : BuiltInProfiles.all()) {
        if (profile.name.equalsIgnoreCase(builtIn.name) && profile.data.equals(builtIn.data)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public Component getNarration() {
      return Component.literal(profile.name);
    }

    @Override
    public void extractContent(
        GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
      boolean isSelected = getSelected() == this;
      boolean isCurrent = ProfileManager.isCurrentProfile(profile);
      boolean isBuiltIn = isBuiltInProfile();

      int x = getContentX();
      int y = getContentY();
      int contentWidth = getContentWidth();

      if (isSelected) {
        graphics.fill(
            ProfileListWidget.this.getX(),
            getY(),
            ProfileListWidget.this.getX() + ProfileListWidget.this.width,
            getY() + getHeight(),
            SELECTED_BG_COLOR);
      } else if (hovered) {
        graphics.fill(
            ProfileListWidget.this.getX(),
            getY(),
            ProfileListWidget.this.getX() + ProfileListWidget.this.width,
            getY() + getHeight(),
            HOVER_BG_COLOR);
      }

      int textX = x + 4;
      int textY = y + 2;

      String indicator = isCurrent ? "\u25CF" : "\u25CB";
      int indicatorColor = isCurrent ? BADGE_ACTIVE_COLOR : 0xFF666666;
      graphics.text(minecraft.font, indicator, textX, textY, indicatorColor, false);

      textX += 14;

      graphics.text(minecraft.font, profile.name, textX, textY, NAME_COLOR, false);

      int badgeX = textX + minecraft.font.width(profile.name) + 8;

      if (isCurrent) {
        String currentBadge = "(Current Setting)";
        graphics.text(minecraft.font, currentBadge, badgeX, textY, BADGE_ACTIVE_COLOR, false);
        badgeX += minecraft.font.width(currentBadge) + 8;
      }

      if (isBuiltIn) {
        String builtinBadge = "(builtin)";
        graphics.text(minecraft.font, builtinBadge, badgeX, textY, BUILTIN_TAG_COLOR, false);
      }

      String description =
          profile.description != null && !profile.description.isEmpty() ? profile.description : "";
      if (!description.isEmpty()) {
        graphics.text(minecraft.font, description, textX, textY + 12, DESCRIPTION_COLOR, false);
      }

      int totalButtonsWidth = BUTTON_WIDTH * 4 + BUTTON_SPACING * 3;
      int buttonStartX = x + contentWidth - totalButtonsWidth - 4;
      int buttonY = y + (ENTRY_HEIGHT - ButtonRenderer.BUTTON_HEIGHT) / 2;

      ButtonRenderer.renderButton(
          minecraft,
          graphics,
          mouseX,
          mouseY,
          buttonStartX,
          buttonY,
          BUTTON_WIDTH,
          "Apply",
          ButtonRenderer.STYLE_APPLY);
      ButtonRenderer.renderButton(
          minecraft,
          graphics,
          mouseX,
          mouseY,
          buttonStartX + BUTTON_WIDTH + BUTTON_SPACING,
          buttonY,
          BUTTON_WIDTH,
          "Rename",
          ButtonRenderer.STYLE_RENAME);
      ButtonRenderer.renderButton(
          minecraft,
          graphics,
          mouseX,
          mouseY,
          buttonStartX + (BUTTON_WIDTH + BUTTON_SPACING) * 2,
          buttonY,
          BUTTON_WIDTH,
          "Edit",
          ButtonRenderer.STYLE_EDIT);
      ButtonRenderer.renderButton(
          minecraft,
          graphics,
          mouseX,
          mouseY,
          buttonStartX + (BUTTON_WIDTH + BUTTON_SPACING) * 3,
          buttonY,
          BUTTON_WIDTH,
          "Delete",
          ButtonRenderer.STYLE_DELETE);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      if (event.button() != 0) {
        return super.mouseClicked(event, doubleClick);
      }

      int mouseX = (int) event.x();
      int mouseY = (int) event.y();

      int contentWidth = getContentWidth();
      int totalButtonsWidth = BUTTON_WIDTH * 4 + BUTTON_SPACING * 3;
      int buttonStartX = getContentX() + contentWidth - totalButtonsWidth - 4;
      int buttonY = getContentY() + (ENTRY_HEIGHT - ButtonRenderer.BUTTON_HEIGHT) / 2;

      if (mouseY >= buttonY && mouseY < buttonY + ButtonRenderer.BUTTON_HEIGHT) {
        if (ButtonRenderer.isMouseOver(mouseX, mouseY, buttonStartX, buttonY, BUTTON_WIDTH)) {
          if (onProfileApply != null) {
            onProfileApply.accept(profile);
          }
          return true;
        }
        if (ButtonRenderer.isMouseOver(
            mouseX, mouseY, buttonStartX + BUTTON_WIDTH + BUTTON_SPACING, buttonY, BUTTON_WIDTH)) {
          if (onProfileRename != null) {
            onProfileRename.accept(profile);
          }
          return true;
        }
        if (ButtonRenderer.isMouseOver(
            mouseX,
            mouseY,
            buttonStartX + (BUTTON_WIDTH + BUTTON_SPACING) * 2,
            buttonY,
            BUTTON_WIDTH)) {
          if (onProfileEdit != null) {
            onProfileEdit.accept(profile);
          }
          return true;
        }
        if (ButtonRenderer.isMouseOver(
            mouseX,
            mouseY,
            buttonStartX + (BUTTON_WIDTH + BUTTON_SPACING) * 3,
            buttonY,
            BUTTON_WIDTH)) {
          if (onProfileDelete != null) {
            onProfileDelete.accept(profile);
          }
          return true;
        }
      }

      long currentTime = System.currentTimeMillis();
      boolean isDoubleClick = (currentTime - lastClickTime) < DOUBLE_CLICK_THRESHOLD;
      lastClickTime = currentTime;

      if (isDoubleClick) {
        if (onProfileApply != null) {
          onProfileApply.accept(profile);
        }
        return true;
      }

      return super.mouseClicked(event, doubleClick);
    }
  }
}
