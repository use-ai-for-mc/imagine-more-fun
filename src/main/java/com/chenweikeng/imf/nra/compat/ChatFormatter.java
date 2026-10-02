package com.chenweikeng.imf.nra.compat;

import com.chenweikeng.monkeycraft_api.v1.ChatMessageResult;
import com.chenweikeng.monkeycraft_api.v1.IncomingChatContext;
import com.chenweikeng.monkeycraft_api.v1.OutgoingChatContext;
import com.vdurmont.emoji.EmojiManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

final class ChatFormatter {
  private ChatFormatter() {}

  static ChatMessageResult onIncomingChat(IncomingChatContext context) {
    Component message = context.getMessage();
    String senderUuid = context.getSenderUuid();
    String senderName = context.getSenderName();

    return processIncomingMessage(context, message, senderUuid, senderName);
  }

  static ChatMessageResult onOutgoingChat(OutgoingChatContext context) {
    String message = context.getMessage();

    return processOutgoingMessage(context, message);
  }

  private static ChatMessageResult processIncomingMessage(
      IncomingChatContext context, Component message, String senderUuid, String senderName) {
    boolean[] modified = {false};
    Component filtered = filterComponent(message, modified);

    if (modified[0]) {
      context.setMessage(filtered);
      return ChatMessageResult.MODIFY;
    }

    return ChatMessageResult.PASS;
  }

  private static final int IF_PLUS_COLOR = 0x2E86DE;
  private static final String IF_PLUS_TEXT = "IF+ Subscription Streak";
  private static final int WHITE_COLOR = 0xFFFFFF;
  private static final int EMOJI_TOOLTIP_COLOR = 0xC8D6E5;

  private static boolean containsEmoji(String input) {
    return EmojiManager.isEmoji(input);
  }

  private static boolean isAllowedLatinCharacter(int codePoint) {
    Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
    if (script == Character.UnicodeScript.LATIN) {
      return true;
    }
    Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
    return block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT
        || block == Character.UnicodeBlock.LATIN_EXTENDED_A
        || block == Character.UnicodeBlock.LATIN_EXTENDED_B
        || block == Character.UnicodeBlock.GENERAL_PUNCTUATION;
  }

  private static boolean isUnknownUnicode(int codePoint) {
    if (codePoint <= 127) {
      return false;
    }
    if (codePoint == 0x00A7) {
      return false;
    }
    String str = new String(Character.toChars(codePoint));
    if (containsEmoji(str)) {
      return false;
    }
    if (REPLACEMENT_TABLE.containsKey(str)) {
      return false;
    }
    if (isAllowedLatinCharacter(codePoint)) {
      return false;
    }
    return true;
  }

  private static MutableComponent replaceUnknownUnicode(String text, Style style) {
    MutableComponent result = Component.empty();
    StringBuilder sb = new StringBuilder();

    for (int i = 0; i < text.length(); ) {
      int codePoint = text.codePointAt(i);
      int charCount = Character.charCount(codePoint);

      if (isUnknownUnicode(codePoint)) {
        if (sb.length() > 0) {
          result.append(Component.literal(sb.toString()).withStyle(style));
          sb.setLength(0);
        }
        result.append(Component.literal("\uFFFD").withStyle(style));
      } else {
        sb.appendCodePoint(codePoint);
      }
      i += charCount;
    }

    if (sb.length() > 0) {
      result.append(Component.literal(sb.toString()).withStyle(style));
    }

    return result;
  }

  private static int findFirstReplacementChar(String text) {
    for (int i = 0; i < text.length(); i++) {
      String ch = text.substring(i, i + 1);
      if (REPLACEMENT_TABLE.containsKey(ch)) {
        return i;
      }
    }
    return -1;
  }

  private static MutableComponent applyReplacements(String text, Style style) {
    MutableComponent result = Component.empty();
    int matchIndex = findFirstReplacementChar(text);

    if (matchIndex < 0) {
      result.append(replaceUnknownUnicode(text, style));
      return result;
    }

    String before = text.substring(0, matchIndex);
    String matchChar = text.substring(matchIndex, matchIndex + 1);
    String after = text.substring(matchIndex + 1);

    if (!before.isEmpty()) {
      result.append(replaceUnknownUnicode(before, style));
    }

    Function<Style, Component> replacementFactory = REPLACEMENT_TABLE.get(matchChar);
    if (replacementFactory != null) {
      result.append(replacementFactory.apply(style));
    }

    while (!after.isEmpty()) {
      int nextMatch = findFirstReplacementChar(after);
      if (nextMatch < 0) {
        result.append(replaceUnknownUnicode(after, style));
        break;
      }
      String nextBefore = after.substring(0, nextMatch);
      String nextChar = after.substring(nextMatch, nextMatch + 1);
      after = after.substring(nextMatch + 1);

      if (!nextBefore.isEmpty()) {
        result.append(replaceUnknownUnicode(nextBefore, style));
      }

      Function<Style, Component> nextReplacementFactory = REPLACEMENT_TABLE.get(nextChar);
      if (nextReplacementFactory != null) {
        result.append(nextReplacementFactory.apply(style));
      }
    }

    return result;
  }

  private static Component filterHoverText(Component hoverText, boolean[] modified) {
    MutableComponent result = Component.empty();

    hoverText.visit(
        (style, text) -> {
          if (text.isEmpty()) {
            return Optional.empty();
          }

          int matchIndex = findFirstReplacementChar(text);
          if (matchIndex >= 0) {
            modified[0] = true;
            result.append(applyReplacements(text, style));
          } else {
            MutableComponent finalComponent = replaceUnknownUnicode(text, style);
            if (finalComponent.getSiblings().size() > 1
                || (finalComponent.getSiblings().isEmpty()
                    && !finalComponent.getString().equals(text))) {
              modified[0] = true;
            }
            result.append(finalComponent);
          }

          return Optional.empty();
        },
        hoverText.getStyle());

    return result;
  }

  private static final Map<String, Function<Style, Component>> REPLACEMENT_TABLE = new HashMap<>();

  static {
    REPLACEMENT_TABLE.put(
        "\u4E00", s -> Component.literal("[Imagineer]").withStyle(s.withColor(0x0176BC)));
    REPLACEMENT_TABLE.put(
        "\u4E01", s -> Component.literal("[Developer]").withStyle(s.withColor(0xFA8231)));
    REPLACEMENT_TABLE.put(
        "\u4E02", s -> Component.literal("[Manager]").withStyle(s.withColor(0x8406DE)));
    REPLACEMENT_TABLE.put(
        "\u4E03", s -> Component.literal("[Character]").withStyle(s.withColor(0x2CC129)));
    REPLACEMENT_TABLE.put(
        "\u4E04", s -> Component.literal("[Builder]").withStyle(s.withColor(0x1E5DEB)));
    REPLACEMENT_TABLE.put(
        "\u4E05", s -> Component.literal("[Operator]").withStyle(s.withColor(0x8406DE)));
    REPLACEMENT_TABLE.put(
        "\u4E06", s -> Component.literal("[Coordinator]").withStyle(s.withColor(0xDB4BC6)));
    REPLACEMENT_TABLE.put(
        "\u4E07", s -> Component.literal("[Cast Member]").withStyle(s.withColor(0xFECA57)));
    REPLACEMENT_TABLE.put(
        "\u4E08", s -> Component.literal("[Tour Guide]").withStyle(s.withColor(0x0C9201)));
    REPLACEMENT_TABLE.put(
        "\u4E09", s -> Component.literal("[Trainee]").withStyle(s.withColor(0x0C9201)));
    REPLACEMENT_TABLE.put(
        "\u4E0A", s -> Component.literal("[DVC+]").withStyle(s.withColor(0x0176BC)));
    REPLACEMENT_TABLE.put(
        "\u4E16", s -> Component.literal("[Media]").withStyle(s.withColor(0x0175B9)));
    REPLACEMENT_TABLE.put(
        "\u4E0B", s -> Component.literal("[DVC]").withStyle(s.withColor(0x0176BC)));
    REPLACEMENT_TABLE.put(
        "\u4E0C", s -> Component.literal("[Club 33+]").withStyle(s.withColor(0x018178)));
    REPLACEMENT_TABLE.put(
        "\u4E0D", s -> Component.literal("[Club 33]").withStyle(s.withColor(0x018178)));
    REPLACEMENT_TABLE.put(
        "\u4E0E", s -> Component.literal("[D23+]").withStyle(s.withColor(0x0175B9)));
    REPLACEMENT_TABLE.put(
        "\u4E0F", s -> Component.literal("[D23]").withStyle(s.withColor(0x0175B9)));
    REPLACEMENT_TABLE.put(
        "\u4E10", s -> Component.literal("[Passholder+]").withStyle(s.withColor(0xDB2222)));
    REPLACEMENT_TABLE.put(
        "\u4E11", s -> Component.literal("[Passholder]").withStyle(s.withColor(0xDB2222)));
    REPLACEMENT_TABLE.put(
        "\u4E14", s -> Component.literal("[Guest+]").withStyle(s.withColor(0xA9A9A9)));
    REPLACEMENT_TABLE.put(
        "\u4E15", s -> Component.literal("[Guest]").withStyle(s.withColor(0xA9A9A9)));
    REPLACEMENT_TABLE.put(
        "\u4E12", s -> Component.literal("[VIP]").withStyle(s.withColor(0x0175A9)));
    REPLACEMENT_TABLE.put(
        "\u4E60", s -> Component.literal("[VIP+]").withStyle(s.withColor(0xF368E0)));
    REPLACEMENT_TABLE.put(
        "\u4E89", s -> Component.literal("[Pixel Artist]").withStyle(s.withColor(0x2D83DA)));
    REPLACEMENT_TABLE.put(
        "\u4E98", s -> Component.literal("[Junior Tour Guide]").withStyle(s.withColor(0x0C9201)));
    REPLACEMENT_TABLE.put(
        "\u4E99", s -> Component.literal("[ShowTech]").withStyle(s.withColor(0xBB2DD9)));
    REPLACEMENT_TABLE.put(
        "\u4E9A", s -> Component.literal("[Discord]").withStyle(s.withColor(0x7289DA)));
    REPLACEMENT_TABLE.put(
        "\u4E9E", s -> Component.literal("[Artist]").withStyle(s.withColor(0x47D7F7)));
    REPLACEMENT_TABLE.put(
        "\u4EAC", s -> Component.literal("[Director]").withStyle(s.withColor(0xCA3767)));
    REPLACEMENT_TABLE.put(
        "\u4E17", s -> Component.literal("[Guest+++]").withStyle(s.withColor(0xA9A9A9)));
    REPLACEMENT_TABLE.put(
        "\u6BAC", s -> Component.literal("[Former Staff]").withStyle(s.withColor(0x2B9270)));
    REPLACEMENT_TABLE.put(
        "\u4EF7",
        s -> Component.literal("[Shout]").withStyle(s.withColor(0xEB1A3F).withBold(true)));
    REPLACEMENT_TABLE.put("\uF000", null);
    REPLACEMENT_TABLE.put("\uF001", null);
    REPLACEMENT_TABLE.put("\uF002", null);
    REPLACEMENT_TABLE.put("\uF003", null);
    REPLACEMENT_TABLE.put("\uF004", null);
    REPLACEMENT_TABLE.put("\uF005", null);
    REPLACEMENT_TABLE.put("\uF006", null);
    REPLACEMENT_TABLE.put("\uF007", null);
    REPLACEMENT_TABLE.put("\uF008", null);
    REPLACEMENT_TABLE.put("\uF009", null);
    REPLACEMENT_TABLE.put("\uF00A", null);
    REPLACEMENT_TABLE.put("\uF00B", null);
    REPLACEMENT_TABLE.put("\uF00C", null);
    REPLACEMENT_TABLE.put("\uF00D", null);
    REPLACEMENT_TABLE.put("\uF00E", null);
    REPLACEMENT_TABLE.put("\uF00F", null);
    REPLACEMENT_TABLE.put(
        "\u4E6C", s -> Component.literal("[Uncommon]").withStyle(s.withColor(0x32FF7E)));
    REPLACEMENT_TABLE.put(
        "\u4E67", s -> Component.literal("[Common]").withStyle(s.withColor(0xA9A9A9)));
    REPLACEMENT_TABLE.put(
        "\u4E6B", s -> Component.literal("[Rare]").withStyle(s.withColor(0x54A0FF)));
    REPLACEMENT_TABLE.put(
        "\u4EA5", s -> Component.literal("[Exotic]").withStyle(s.withColor(0x48DBFB)));
    REPLACEMENT_TABLE.put(
        "\u4E68", s -> Component.literal("[Epic]").withStyle(s.withColor(0xBE2EDD)));
    REPLACEMENT_TABLE.put(
        "\u4E69", s -> Component.literal("[Legendary]").withStyle(s.withColor(0xFF9F43)));
    REPLACEMENT_TABLE.put(
        "\u4E6A", s -> Component.literal("[Mythic]").withStyle(s.withColor(0xFD9644)));
    REPLACEMENT_TABLE.put(
        "\u4EA6", s -> Component.literal("[Unobtainable]").withStyle(s.withColor(0x576574)));
    REPLACEMENT_TABLE.put("\u6A3C", s -> Component.literal("\uD83D\uDD25").withStyle(s));
  }

  private static Component filterComponent(Component component, boolean[] modified) {
    MutableComponent result = Component.empty();

    component.visit(
        (style, text) -> {
          if (text.isEmpty()) {
            return Optional.empty();
          }

          HoverEvent hoverEvent = style.getHoverEvent();
          if (hoverEvent != null && hoverEvent.action() == HoverEvent.Action.SHOW_TEXT) {
            HoverEvent.ShowText showText = (HoverEvent.ShowText) hoverEvent;
            Component hoverText = showText.value();
            if (hoverText != null) {
              if (hoverText.getString().equals(IF_PLUS_TEXT)) {
                Style hoverStyle = hoverText.getStyle();
                if (hoverStyle != null
                    && hoverStyle.getColor() != null
                    && hoverStyle.getColor().getValue() == IF_PLUS_COLOR) {
                  modified[0] = true;
                  return Optional.empty();
                }
              }
            }
          }

          TextColor color = style.getColor();
          boolean isWhite = color != null && color.getValue() == WHITE_COLOR;

          int matchIndex = findFirstReplacementChar(text);
          if (matchIndex >= 0) {
            modified[0] = true;
            MutableComponent textWithReplacements = applyReplacements(text, style);
            Style newStyle = style;
            if (hoverEvent != null && hoverEvent.action() == HoverEvent.Action.SHOW_TEXT) {
              HoverEvent.ShowText showText = (HoverEvent.ShowText) hoverEvent;
              Component hoverTextComponent = showText.value();
              if (hoverTextComponent != null) {
                boolean[] hoverModified = {false};
                Component newHoverText = filterHoverText(hoverTextComponent, hoverModified);
                if (hoverModified[0]) {
                  modified[0] = true;
                  newStyle = style.withHoverEvent(new HoverEvent.ShowText(newHoverText));
                }
              }
            }
            if (newStyle != style) {
              textWithReplacements.setStyle(newStyle);
            }
            result.append(textWithReplacements);
            return Optional.empty();
          }

          if (text.codePointCount(0, text.length()) == 1
              && !containsEmoji(text)
              && hoverEvent != null
              && hoverEvent.action() == HoverEvent.Action.SHOW_TEXT
              && isWhite) {
            HoverEvent.ShowText showText = (HoverEvent.ShowText) hoverEvent;
            Component hoverTextComponent = showText.value();
            if (hoverTextComponent != null) {
              String hoverString = hoverTextComponent.getString();
              if (hoverString.startsWith(":")) {
                Style hoverStyle = hoverTextComponent.getStyle();
                if (hoverStyle != null
                    && hoverStyle.getColor() != null
                    && hoverStyle.getColor().getValue() == EMOJI_TOOLTIP_COLOR) {
                  modified[0] = true;
                  result.append(Component.literal(hoverString));
                  return Optional.empty();
                }
              }
            }
          }

          Style newStyle = style;
          if (hoverEvent != null && hoverEvent.action() == HoverEvent.Action.SHOW_TEXT) {
            HoverEvent.ShowText showText = (HoverEvent.ShowText) hoverEvent;
            Component hoverTextComponent = showText.value();
            if (hoverTextComponent != null) {
              boolean[] hoverModified = {false};
              Component newHoverText = filterHoverText(hoverTextComponent, hoverModified);
              if (hoverModified[0]) {
                modified[0] = true;
                newStyle = style.withHoverEvent(new HoverEvent.ShowText(newHoverText));
              }
            }
          }

          MutableComponent finalComponent = replaceUnknownUnicode(text, newStyle);
          if (finalComponent.getSiblings().size() > 1
              || (finalComponent.getSiblings().isEmpty()
                  && !finalComponent.getString().equals(text))) {
            modified[0] = true;
          }
          result.append(finalComponent);

          return Optional.empty();
        },
        component.getStyle());

    return result;
  }

  private static ChatMessageResult processOutgoingMessage(
      OutgoingChatContext context, String message) {
    String normalized =
        message
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .replace('\u201C', '"')
            .replace('\u201D', '"')
            .replace('\u2014', '-')
            .replace('\u2013', '-')
            .replace("\u2026", "...")
            .replace('\u00A0', ' ');

    if (!normalized.equals(message)) {
      context.setMessage(normalized);
      return ChatMessageResult.MODIFY;
    }
    return ChatMessageResult.PASS;
  }
}
