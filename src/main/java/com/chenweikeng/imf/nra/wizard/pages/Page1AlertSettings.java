package com.chenweikeng.imf.nra.wizard.pages;

import com.chenweikeng.imf.nra.config.AudioBoostReminderMode;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.handler.ScoreboardHandler;
import com.chenweikeng.imf.nra.wizard.WizardPage;
import com.chenweikeng.imf.nra.wizard.layout.RenderBlock;
import com.chenweikeng.imf.nra.wizard.layout.VerticalAlignment;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class Page1AlertSettings extends WizardPage {

  private static final List<SoundOption> SOUND_OPTIONS =
      List.of(
          new SoundOption("XP Orb Pick", "entity.experience_orb.pickup"),
          new SoundOption("Note Pling", "block.note_block.pling"),
          new SoundOption("Bell Use", "block.bell.use"),
          new SoundOption("Villager Hurt", "entity.villager.hurt"),
          new SoundOption("Entry Whistle", "entry.whistle"),
          new SoundOption("Fastpass Redeem", "fastpass.redeem"),
          new SoundOption("Pin Hoarder #1", "entity.parrot.imitate.vindicator"),
          new SoundOption("Pin Hoarder #2", "entity.vindicator.ambient"),
          new SoundOption("Achievement Unlock", "achievement.unlock"),
          new SoundOption("Magicpass Level Up", "magicpass.levelup"),
          new SoundOption("Enchantment Table Use", "block.enchantment_table.use"),
          new SoundOption("Ride Complete", "ride.complete"));

  private record SoundOption(String displayName, String soundId) {}

  public Page1AlertSettings() {
    super(0);
  }

  @Override
  public Component getTitle() {
    return literal("Alert Settings");
  }

  @Override
  public List<RenderBlock> getBlocks(Minecraft client) {
    List<RenderBlock> blocks = new java.util.ArrayList<>();

    boolean isEnabled = ModConfig.currentSetting.enabled;

    List<RenderBlock> leftColumn = new java.util.ArrayList<>();
    leftColumn.add(text(intro()));
    leftColumn.add(separator(30));
    leftColumn.add(text(buildEnableSection(isEnabled)));
    leftColumn.add(separator(30));
    if (isEnabled) {
      leftColumn.addAll(soundSectionBlocks());
    }

    List<RenderBlock> rightColumn = new java.util.ArrayList<>();
    rightColumn.add(text(silentSection()));
    rightColumn.add(separator(30));
    rightColumn.addAll(audioBoostSectionBlocks());

    blocks.add(
        row(
            column(leftColumn.toArray(new RenderBlock[0])),
            column(rightColumn.toArray(new RenderBlock[0]))));

    if (ScoreboardHandler.getInstance().isScoreboardEmpty()) {
      blocks.add(spacer(30));

      Component text =
          Component.literal("The mod requires ")
              .withStyle(ChatFormatting.BOLD, ChatFormatting.DARK_RED)
              .append(
                  Component.literal("/sb")
                      .withStyle(
                          s ->
                              s.withColor(ChatFormatting.AQUA)
                                  .withUnderlined(true)
                                  .withClickEvent(
                                      new ClickEvent.RunCommand("wizard_action:command:sb"))))
              .append(
                  Component.literal(
                      " to receive scoreboard from server. You can hide scoreboard in this wizard later if you want."))
              .withStyle(ChatFormatting.BOLD, ChatFormatting.DARK_RED);

      blocks.add(text(text));
    }

    return blocks;
  }

  @Override
  protected boolean readyToGoNext() {
    return !ScoreboardHandler.getInstance().isScoreboardEmpty();
  }

  private Component intro() {
    return Component.empty()
        .append(bold("Alerts"))
        .append(literal(" help you know when you're not riding.\n\n"))
        .append(literal("The mod checks every "))
        .append(colored("10 seconds", ChatFormatting.YELLOW))
        .append(
            literal(" unless you are riding or in an autograb zone, or if you has recently moved"));
  }

  private Component buildEnableSection(boolean isEnabled) {
    Component statusText =
        isEnabled
            ? colored("Enabled", ChatFormatting.GREEN)
            : colored("Disabled", ChatFormatting.RED);

    return Component.empty()
        .append(bold("Enable Alerts: "))
        .append(statusText)
        .append(literal("  "))
        .append(
            isEnabled
                ? link("Disable", "config:enabled:false")
                : link("Enable", "config:enabled:true"));
  }

  private List<RenderBlock> soundSectionBlocks() {
    List<RenderBlock> blocks = new java.util.ArrayList<>();

    Component header = literal("");
    header = append(header, bold("Choose the Alert Sound\n\n"));
    header = append(header, literal(" Current: "));
    header = append(header, colored("♪ ", ChatFormatting.YELLOW));
    header = append(header, colored(getCurrentSoundName(), ChatFormatting.GREEN));
    blocks.add(text(header));

    int mid = (SOUND_OPTIONS.size() + 1) / 2;
    Component leftOptions = buildOptionsList(0, mid);
    Component rightOptions = buildOptionsList(mid, SOUND_OPTIONS.size());

    blocks.add(row(text(leftOptions), text(rightOptions)));

    return blocks;
  }

  private Component buildOptionsList(int start, int end) {
    String currentSoundId = ModConfig.currentSetting.soundId;
    Component content = literal("");
    for (int i = start; i < end; i++) {
      SoundOption option = SOUND_OPTIONS.get(i);
      boolean isCurrent = option.soundId.equals(currentSoundId);
      content = append(content, literal("\n♪ "));
      Component nameComponent =
          isCurrent
              ? link(option.displayName, "sound_preview:" + option.soundId, ChatFormatting.GREEN)
              : link(option.displayName, "sound_preview:" + option.soundId);
      content = append(content, nameComponent);
      content = append(content, literal(" "));
      content = append(content, link("[Select]", "config:soundId:" + option.soundId));
    }
    return content;
  }

  private Component append(Component base, Component toAppend) {
    return base.copy().append(toAppend);
  }

  private String getCurrentSoundName() {
    String currentId = ModConfig.currentSetting.soundId;
    for (SoundOption option : SOUND_OPTIONS) {
      if (option.soundId.equals(currentId)) {
        return option.displayName;
      }
    }
    String subtitleKey = "subtitles." + currentId;
    if (I18n.exists(subtitleKey)) {
      return capitalizeEachWord(I18n.get(subtitleKey));
    }
    return "minecraft:" + currentId;
  }

  private String capitalizeEachWord(String text) {
    String[] words = text.split(" ");
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < words.length; i++) {
      if (i > 0) result.append(" ");
      if (!words[i].isEmpty()) {
        result.append(Character.toUpperCase(words[i].charAt(0)));
        if (words[i].length() > 1) {
          result.append(words[i].substring(1));
        }
      }
    }
    return result.toString();
  }

  private Component silentSection() {
    boolean isSilent = ModConfig.currentSetting.silent;
    Component statusText =
        isSilent
            ? colored("Enabled", ChatFormatting.GREEN)
            : colored("Disabled", ChatFormatting.RED);

    Component content = Component.literal("");
    content = append(content, bold("Suppress Game Sounds"));
    content = append(content, literal("\n\n"));
    content = append(content, literal("Mute game sounds while riding? "));
    content = append(content, statusText);
    content = append(content, literal("  "));
    content = append(content, isEnabledLink(isSilent, "silent"));
    content = append(content, literal("\n\n"));
    content = append(content, literal("(e.g., loud explosions on "));
    content = append(content, colored("Disneyland Railroad", ChatFormatting.GOLD));
    content = append(content, literal(" and "));
    content = append(content, colored("Big Thunder Mountain Railroad", ChatFormatting.GOLD));
    content = append(content, literal(")"));
    return content;
  }

  private List<RenderBlock> audioBoostSectionBlocks() {
    List<RenderBlock> blocks = new java.util.ArrayList<>();

    Component header = literal("");
    header = append(header, bold("Audio Boost Reminder"));
    header = append(header, literal("\n\n"));
    blocks.add(text(header));

    Identifier img1 =
        Identifier.fromNamespaceAndPath("not-riding-alert", "textures/missing-audio-1.png");
    Identifier img2 =
        Identifier.fromNamespaceAndPath("not-riding-alert", "textures/missing-audio-2.png");
    blocks.add(row(VerticalAlignment.CENTER, image(img2, 145, 36), image(img1, 132, 23)));

    Component content = literal("");
    content = append(content, literal("Audio Boost gives "));
    content = append(content, colored("1.25x", ChatFormatting.YELLOW));
    content = append(content, literal(" bonus ride rewards when connected to OpenAudioMc.\n\n"));
    content = append(content, literal("Shows "));
    content =
        append(
            content,
            Component.literal("MISSING AUDIO BOOST")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
    content = append(content, literal(" in the action bar when not connected.\n\n"));
    content = append(content, literal("Reminder mode:\n"));
    content = append(content, audioBoostModeLinks());
    blocks.add(text(content));

    return blocks;
  }

  private Component isEnabledLink(boolean current, String configKey) {
    boolean newValue = !current;
    return link(newValue ? "Enable" : "Disable", "config:" + configKey + ":" + newValue);
  }

  private Component audioBoostModeLinks() {
    AudioBoostReminderMode current = ModConfig.currentSetting.audioBoostReminderMode;

    Component content = Component.literal("");
    content = append(content, modeLink("Disabled", AudioBoostReminderMode.DISABLED, current));
    content = append(content, literal(" | "));
    content =
        append(
            content,
            modeLink("Only when riding", AudioBoostReminderMode.ONLY_WHEN_RIDING, current));
    content = append(content, literal(" | "));
    content = append(content, modeLink("Always", AudioBoostReminderMode.ALWAYS, current));
    return content;
  }

  private Component modeLink(
      String label, AudioBoostReminderMode mode, AudioBoostReminderMode current) {
    if (mode == current) {
      return colored(label, ChatFormatting.GREEN);
    }
    return link(label, "config:audioBoostReminderMode:" + mode.name());
  }
}
