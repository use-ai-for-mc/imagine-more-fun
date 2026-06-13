package com.chenweikeng.imf.nra.config.ui;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import me.shedaniel.clothconfig2.gui.entries.TooltipListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;

/**
 * A Cloth Config entry that holds no value: a label on the left and a button on the right that
 * opens another screen. The button label is re-evaluated every frame so it can reflect state
 * changed by the opened screen (e.g. an override count).
 */
public class ScreenOpenButtonEntry extends TooltipListEntry<Object> {
  private static final int BUTTON_WIDTH = 150;

  private final Button button;
  private final Supplier<Component> buttonLabel;

  public ScreenOpenButtonEntry(
      Component fieldName,
      Supplier<Component> buttonLabel,
      Runnable onPress,
      Supplier<Optional<Component[]>> tooltipSupplier) {
    super(fieldName, tooltipSupplier);
    this.buttonLabel = buttonLabel;
    this.button =
        Button.builder(buttonLabel.get(), b -> onPress.run())
            .bounds(0, 0, BUTTON_WIDTH, 20)
            .build();
  }

  @Override
  public Object getValue() {
    return null;
  }

  @Override
  public Optional<Object> getDefaultValue() {
    return Optional.empty();
  }

  @Override
  public boolean isEdited() {
    return false;
  }

  @Override
  public void save() {}

  @Override
  public void render(
      GuiGraphics graphics,
      int index,
      int y,
      int x,
      int entryWidth,
      int entryHeight,
      int mouseX,
      int mouseY,
      boolean isHovered,
      float delta) {
    super.render(graphics, index, y, x, entryWidth, entryHeight, mouseX, mouseY, isHovered, delta);
    Minecraft minecraft = Minecraft.getInstance();
    graphics.drawString(
        minecraft.font, getDisplayedFieldName(), x, y + 6, getPreferredTextColor(), false);
    button.setMessage(buttonLabel.get());
    button.setX(x + entryWidth - BUTTON_WIDTH);
    button.setY(y);
    button.render(graphics, mouseX, mouseY, delta);
  }

  @Override
  public List<? extends GuiEventListener> children() {
    return List.of(button);
  }

  @Override
  public List<? extends NarratableEntry> narratables() {
    return List.of(button);
  }
}
