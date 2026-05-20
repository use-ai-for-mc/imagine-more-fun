package com.chenweikeng.imf.nra.wizard.pages;

import com.chenweikeng.imf.nra.config.ClosedCaptionMode;
import com.chenweikeng.imf.nra.config.FullbrightMode;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.wizard.WizardPage;
import com.chenweikeng.imf.nra.wizard.layout.RenderBlock;
import com.chenweikeng.imf.nra.wizard.layout.VerticalAlignment;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class Page2VisualPreferences extends WizardPage {

  public Page2VisualPreferences() {
    super(1);
  }

  @Override
  public Component getTitle() {
    return literal("Visual Preferences");
  }

  @Override
  public List<RenderBlock> getBlocks(Minecraft client) {
    List<RenderBlock> blocks = new java.util.ArrayList<>();

    List<RenderBlock> leftColumn = new java.util.ArrayList<>();
    leftColumn.add(text(intro()));
    leftColumn.add(separator(30));
    leftColumn.addAll(blindSectionBlocks());
    leftColumn.add(separator(30));
    leftColumn.addAll(fullbrightSectionBlocks());

    List<RenderBlock> rightColumn = new java.util.ArrayList<>();
    rightColumn.addAll(closedCaptionSectionBlocks());

    blocks.add(
        row(
            column(leftColumn.toArray(new RenderBlock[0])),
            column(rightColumn.toArray(new RenderBlock[0]))));

    return blocks;
  }

  private Component intro() {
    return literal("Customize how the game looks while playing.");
  }

  private List<RenderBlock> blindSectionBlocks() {
    List<RenderBlock> blocks = new java.util.ArrayList<>();

    boolean isBlind = ModConfig.currentSetting.blindWhenRiding;
    Component statusText =
        isBlind
            ? colored("Enabled", ChatFormatting.GREEN)
            : colored("Disabled", ChatFormatting.RED);

    Component header = literal("");
    header = append(header, bold("Blindness When Riding"));
    header = append(header, literal("\n"));
    blocks.add(text(header));

    Identifier imgNo =
        Identifier.fromNamespaceAndPath("not-riding-alert", "textures/blindness-no.png");
    Identifier imgYes =
        Identifier.fromNamespaceAndPath("not-riding-alert", "textures/blindness-yes.png");
    blocks.add(row(VerticalAlignment.CENTER, image(imgNo, 120, 64), image(imgYes, 120, 64)));

    Component content = literal("");
    content = append(content, literal("Reduce distractions when you are on the rides."));
    content = append(content, literal("\n\n"));
    content = append(content, literal("Dim the screen when on a ride? "));
    content = append(content, statusText);
    content = append(content, literal("  "));
    content = append(content, isEnabledLink(isBlind, "blindWhenRiding"));
    blocks.add(text(content));

    return blocks;
  }

  private List<RenderBlock> fullbrightSectionBlocks() {
    List<RenderBlock> blocks = new java.util.ArrayList<>();

    FullbrightMode current = ModConfig.currentSetting.fullbrightMode;
    boolean whenRiding =
        current == FullbrightMode.ONLY_WHEN_RIDING || current == FullbrightMode.ALWAYS;
    boolean whenNotRiding =
        current == FullbrightMode.ONLY_WHEN_NOT_RIDING || current == FullbrightMode.ALWAYS;

    Component header = literal("");
    header = append(header, bold("Fullbright Mode"));
    header = append(header, literal("\n\n"));
    header = append(header, literal("Control when full brightness is applied."));
    blocks.add(text(header));

    Component content = literal("");
    content = append(content, literal("\n"));
    content = append(content, buildToggleLine("When riding", whenRiding, "fullbrightWhenRiding"));
    content = append(content, literal("\n"));
    content =
        append(
            content, buildToggleLine("When not riding", whenNotRiding, "fullbrightWhenNotRiding"));
    blocks.add(text(content));

    return blocks;
  }

  private Component buildToggleLine(String label, boolean isEnabled, String configKey) {
    Component statusText =
        isEnabled
            ? colored("Enabled", ChatFormatting.GREEN)
            : colored("Disabled", ChatFormatting.RED);

    return Component.empty()
        .append(literal(label + ": "))
        .append(statusText)
        .append(literal("  "))
        .append(this.isEnabledLink(isEnabled, configKey));
  }

  private List<RenderBlock> closedCaptionSectionBlocks() {
    List<RenderBlock> blocks = new java.util.ArrayList<>();
    ClosedCaptionMode current = ModConfig.currentSetting.closedCaptionMode;

    Component header = literal("");
    header = append(header, bold("Closed Caption Mode"));
    header = append(header, literal("\n\n"));
    header =
        append(
            header,
            literal(
                "Control how closed caption should be displayed in ImagineFun. These are scripts from skippers' introductions in "));
    header = append(header, colored("Jungle Cruise", ChatFormatting.GOLD));
    header = append(header, literal(", songs in "));
    header = append(header, colored("Tiana's Bayou Adventure", ChatFormatting.GOLD));
    header = append(header, literal(", and the battle scene in "));
    header = append(header, colored("Star Wars: Rise of the Resistance", ChatFormatting.GOLD));
    header = append(header, literal(".\n\n"));
    header = append(header, colored("[Note]", ChatFormatting.YELLOW));
    header =
        append(
            header,
            literal(
                " This feature requires first enabling \"Message Settings\" -> \"Closed Captions\" in "));
    header = append(header, link("/settings", "command:settings"));
    header = append(header, literal(" first."));
    blocks.add(text(header));

    blocks.add(spacer(10));

    Identifier img0 = Identifier.fromNamespaceAndPath("not-riding-alert", "textures/cc-0.png");
    Identifier img1 = Identifier.fromNamespaceAndPath("not-riding-alert", "textures/cc-1.png");
    Identifier img2 = Identifier.fromNamespaceAndPath("not-riding-alert", "textures/cc-2.png");

    List<RenderBlock> disabledCol = new java.util.ArrayList<>();
    disabledCol.add(image(img0, 314, 61));
    disabledCol.add(
        text(ccModeLine("In Chat", ClosedCaptionMode.NONE, current, "Keep CC in chat")));
    disabledCol.add(
        text(
            ccModeLine(
                "Hidden",
                ClosedCaptionMode.HIDDEN,
                current,
                "Suppress CC from both chat and overlay")));
    blocks.add(row(VerticalAlignment.CENTER, column(disabledCol.toArray(new RenderBlock[0]))));

    blocks.add(spacer(10));

    List<RenderBlock> plainCol = new java.util.ArrayList<>();
    plainCol.add(image(img1, 213, 62));
    plainCol.add(text(ccModeLine("Plain", ClosedCaptionMode.PLAIN, current, "White text overlay")));

    List<RenderBlock> recoloredCol = new java.util.ArrayList<>();
    recoloredCol.add(image(img2, 213, 62));
    recoloredCol.add(
        text(
            ccModeLine(
                "Recolored", ClosedCaptionMode.RECOLORED, current, "Random color per speaker")));

    blocks.add(
        row(
            VerticalAlignment.TOP,
            column(plainCol.toArray(new RenderBlock[0])),
            column(recoloredCol.toArray(new RenderBlock[0]))));

    return blocks;
  }

  private Component ccModeLine(
      String label, ClosedCaptionMode mode, ClosedCaptionMode current, String description) {
    return Component.empty()
        .append(ccModeLink(label, mode, current))
        .append(literal(" - "))
        .append(literal(description));
  }

  private Component append(Component base, Component toAppend) {
    return base.copy().append(toAppend);
  }

  private Component isEnabledLink(boolean current, String configKey) {
    boolean newValue = !current;
    return link(newValue ? "Enable" : "Disable", "config:" + configKey + ":" + newValue);
  }

  private Component ccModeLink(String label, ClosedCaptionMode mode, ClosedCaptionMode current) {
    if (mode == current) {
      return colored(label, ChatFormatting.GREEN);
    }
    return link(label, "config:closedCaptionMode:" + mode.name());
  }
}
