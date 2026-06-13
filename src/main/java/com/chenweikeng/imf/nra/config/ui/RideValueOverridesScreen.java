package com.chenweikeng.imf.nra.config.ui;

import com.chenweikeng.imf.nra.config.ConfigSetting;
import com.chenweikeng.imf.nra.config.profile.ui.ButtonRenderer;
import com.chenweikeng.imf.nra.ride.RideName;
import com.chenweikeng.imf.nra.util.TimeFormatUtil;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Exceptions-only editor for a per-ride integer map: lists just the rides that have a value
 * (usually a handful), each with a free-form number field and a remove button; an add flow picks
 * from the rides that don't have one yet. Mutations apply to the backing map immediately and are
 * persisted via {@code onSave} when the screen closes. Factories configure it for the max goal
 * overrides and advance notice maps.
 */
public class RideValueOverridesScreen extends Screen {
  private static final int PADDING = 20;
  private static final int FOOTER_HEIGHT = 50;
  private static final int BUTTON_HEIGHT = 20;
  private static final int LABEL_COLOR = 0xFFFFFFFF;
  private static final int HINT_COLOR = 0xFFAAAAAA;

  private final Screen parent;
  private final Map<String, Integer> values;
  private final Runnable onSave;
  private final Supplier<String> subtitle;
  private final String emptyHint;
  private final IntSupplier newEntryValue;
  private final int maxDigits;

  /** True while choosing which ride to add an entry for. */
  private boolean picking = false;

  private boolean dirty = false;

  private OverrideListWidget list;
  private Button addButton;
  private Button doneButton;

  /** Editor for {@link ConfigSetting#rideGoalOverrides}: per-ride max goals. */
  public static RideValueOverridesScreen maxGoals(
      Screen parent, ConfigSetting profile, Runnable onSave) {
    return new RideValueOverridesScreen(
        parent,
        "Max Goal Overrides",
        profile.rideGoalOverrides,
        onSave,
        () ->
            "Rides not listed here use the system goal (" + profile.maxGoal.getDisplayName() + ")",
        "No overrides set — every ride uses the system goal",
        () -> profile.maxGoal.getValue(),
        7);
  }

  /** Editor for {@link ConfigSetting#advanceNoticeSeconds}: warning seconds before ride end. */
  public static RideValueOverridesScreen advanceNotice(
      Screen parent, ConfigSetting profile, Runnable onSave) {
    return new RideValueOverridesScreen(
        parent,
        "Advance Notice",
        profile.advanceNoticeSeconds,
        onSave,
        () -> "Seconds of warning before a listed ride ends; unlisted rides get none",
        "No advance notices set — no warning sound before ride end",
        () -> 5,
        2);
  }

  private RideValueOverridesScreen(
      Screen parent,
      String title,
      Map<String, Integer> values,
      Runnable onSave,
      Supplier<String> subtitle,
      String emptyHint,
      IntSupplier newEntryValue,
      int maxDigits) {
    super(Component.literal(title));
    this.parent = parent;
    this.values = values;
    this.onSave = onSave;
    this.subtitle = subtitle;
    this.emptyHint = emptyHint;
    this.newEntryValue = newEntryValue;
    this.maxDigits = maxDigits;
  }

  @Override
  protected void init() {
    super.init();

    int listY = PADDING + 35;
    int listHeight = height - FOOTER_HEIGHT - listY;
    list = new OverrideListWidget(minecraft, width, listHeight, listY);
    addRenderableWidget(list);

    int footerY = height - FOOTER_HEIGHT + 10;
    addButton =
        Button.builder(Component.literal("+ Add Override"), b -> enterPickMode())
            .bounds(width / 2 - 130, footerY, 125, BUTTON_HEIGHT)
            .build();
    addRenderableWidget(addButton);

    doneButton =
        Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(width / 2 + 5, footerY, 125, BUTTON_HEIGHT)
            .build();
    addRenderableWidget(doneButton);

    rebuild();
  }

  private void enterPickMode() {
    picking = true;
    rebuild();
  }

  private void exitPickMode() {
    picking = false;
    rebuild();
  }

  private void rebuild() {
    list.rebuildEntries(picking);
    addButton.visible = !picking;
    doneButton.setMessage(Component.literal(picking ? "Cancel" : "Done"));
  }

  private void addOverride(RideName ride) {
    values.put(ride.toMatchString(), newEntryValue.getAsInt());
    dirty = true;
    exitPickMode();
  }

  private void removeOverride(RideName ride) {
    values.remove(ride.toMatchString());
    dirty = true;
    rebuild();
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    graphics.fill(0, 0, width, height, 0xCC000000);

    Component styledTitle = getTitle().copy().withStyle(ChatFormatting.BOLD, ChatFormatting.AQUA);
    graphics.drawCenteredString(font, styledTitle, width / 2, 8, LABEL_COLOR);

    String hint = picking ? "Select a ride to add" : subtitle.get();
    graphics.drawCenteredString(font, Component.literal(hint), width / 2, 24, HINT_COLOR);

    super.render(graphics, mouseX, mouseY, delta);

    if (!picking && values.isEmpty()) {
      graphics.drawCenteredString(
          font, Component.literal(emptyHint), width / 2, height / 2, HINT_COLOR);
    }
  }

  @Override
  public boolean shouldCloseOnEsc() {
    return true;
  }

  @Override
  public void onClose() {
    if (picking) {
      exitPickMode();
      return;
    }
    if (dirty) {
      dirty = false;
      if (onSave != null) {
        onSave.run();
      }
    }
    if (minecraft != null) {
      minecraft.setScreen(parent);
    }
  }

  private static String formatRideLabel(RideName ride) {
    return String.format(
        "%s (Time: %s)", ride.getDisplayName(), TimeFormatUtil.formatDuration(ride.getRideTime()));
  }

  private class OverrideListWidget extends ContainerObjectSelectionList<OverrideListWidget.Entry> {
    private static final int ENTRY_HEIGHT = 24;

    OverrideListWidget(Minecraft minecraft, int width, int height, int y) {
      super(minecraft, width, height, y, ENTRY_HEIGHT);
    }

    void rebuildEntries(boolean picking) {
      clearEntries();
      for (RideName ride : RideName.sortedByDisplayName()) {
        boolean hasValue = values.containsKey(ride.toMatchString());
        if (picking && !hasValue) {
          addEntry(new PickEntry(ride));
        } else if (!picking && hasValue) {
          addEntry(new OverrideEntry(ride));
        }
      }
      setScrollAmount(0);
    }

    @Override
    public int getRowWidth() {
      return Math.min(OverrideListWidget.this.width - 40, 360);
    }

    abstract class Entry extends ContainerObjectSelectionList.Entry<Entry> {}

    /** A ride that has a value: name, an editable number, and a remove button. */
    class OverrideEntry extends Entry {
      private static final int VALUE_WIDTH = 56;
      private static final int BOX_HEIGHT = 16;
      private static final int REMOVE_WIDTH = 56;
      private static final int GAP = 4;

      private final RideName ride;
      private final EditBox valueBox;

      OverrideEntry(RideName ride) {
        this.ride = ride;
        valueBox =
            new EditBox(minecraft.font, 0, 0, VALUE_WIDTH, BOX_HEIGHT, Component.literal("Value"));
        valueBox.setFilter(text -> text.matches("\\d*"));
        valueBox.setMaxLength(maxDigits);
        Integer current = values.get(ride.toMatchString());
        valueBox.setValue(current != null ? String.valueOf(current) : "");
        valueBox.setResponder(this::commitValue);
      }

      /** Writes any positive value through to the map; an empty box keeps the last value. */
      private void commitValue(String text) {
        int parsed;
        try {
          parsed = Integer.parseInt(text);
        } catch (NumberFormatException e) {
          return;
        }
        if (parsed <= 0) {
          return;
        }
        Integer previous = values.put(ride.toMatchString(), parsed);
        if (previous == null || previous != parsed) {
          dirty = true;
        }
      }

      private int controlsWidth() {
        return VALUE_WIDTH + GAP * 2 + REMOVE_WIDTH;
      }

      @Override
      public List<? extends GuiEventListener> children() {
        return List.of(valueBox);
      }

      @Override
      public List<? extends NarratableEntry> narratables() {
        return List.of(valueBox);
      }

      @Override
      public void renderContent(
          GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float delta) {
        int x = getContentX();
        int y = getContentY();
        int contentWidth = getContentWidth();

        graphics.drawString(
            minecraft.font, ride.getDisplayName(), x + 4, y + 8, LABEL_COLOR, false);

        int controlsX = x + contentWidth - controlsWidth() - 4;
        valueBox.setX(controlsX);
        valueBox.setY(y + (ENTRY_HEIGHT - BOX_HEIGHT) / 2);
        valueBox.render(graphics, mouseX, mouseY, delta);

        int buttonY = y + (ENTRY_HEIGHT - ButtonRenderer.BUTTON_HEIGHT) / 2;
        ButtonRenderer.renderButton(
            minecraft,
            graphics,
            mouseX,
            mouseY,
            controlsX + VALUE_WIDTH + GAP * 2,
            buttonY,
            REMOVE_WIDTH,
            "Remove",
            ButtonRenderer.STYLE_DELETE);
      }

      @Override
      public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
          int mouseX = (int) event.x();
          int mouseY = (int) event.y();
          int controlsX = getContentX() + getContentWidth() - controlsWidth() - 4;
          int buttonY = getContentY() + (ENTRY_HEIGHT - ButtonRenderer.BUTTON_HEIGHT) / 2;
          if (ButtonRenderer.isMouseOver(
              mouseX, mouseY, controlsX + VALUE_WIDTH + GAP * 2, buttonY, REMOVE_WIDTH)) {
            removeOverride(ride);
            return true;
          }
        }
        // Forwards to the edit box via the container default.
        return super.mouseClicked(event, doubleClick);
      }
    }

    /** A ride without a value; clicking it adds one. */
    class PickEntry extends Entry {
      private final RideName ride;

      PickEntry(RideName ride) {
        this.ride = ride;
      }

      @Override
      public List<? extends GuiEventListener> children() {
        return List.of();
      }

      @Override
      public List<? extends NarratableEntry> narratables() {
        return List.of();
      }

      @Override
      public void renderContent(
          GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float delta) {
        if (hovered) {
          graphics.fill(
              OverrideListWidget.this.getX(),
              getY(),
              OverrideListWidget.this.getX() + OverrideListWidget.this.width,
              getY() + getHeight(),
              0x33FFFFFF);
        }
        graphics.drawString(
            minecraft.font,
            formatRideLabel(ride),
            getContentX() + 4,
            getContentY() + 8,
            LABEL_COLOR,
            false);
      }

      @Override
      public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
          addOverride(ride);
          return true;
        }
        return super.mouseClicked(event, doubleClick);
      }
    }
  }
}
