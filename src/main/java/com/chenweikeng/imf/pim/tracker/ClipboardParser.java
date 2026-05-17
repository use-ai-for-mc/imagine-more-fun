package com.chenweikeng.imf.pim.tracker;

import com.chenweikeng.imf.pim.PimClient;
import com.chenweikeng.imf.pim.pin.PinShortNameGenerator;
import com.chenweikeng.imf.pim.screen.PinDetailHandler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class ClipboardParser {
  private static final ClipboardParser INSTANCE = new ClipboardParser();

  private String lastClipboard = "";
  private long lastCheckTime = 0;
  private static final long CHECK_INTERVAL_MS = 500;

  public static ClipboardParser getInstance() {
    return INSTANCE;
  }

  public void tick() {
    Minecraft mc = Minecraft.getInstance();
    long currentTime = System.currentTimeMillis();

    if (currentTime - lastCheckTime < CHECK_INTERVAL_MS) {
      return;
    }

    lastCheckTime = currentTime;

    if (mc.player == null) {
      return;
    }

    try {
      String currentClipboard = mc.keyboardHandler.getClipboard();
      if (currentClipboard == null) {
        currentClipboard = "";
      }

      if (currentClipboard.equals(lastClipboard)) {
        return;
      }

      lastClipboard = currentClipboard;

      if (!currentClipboard.contains("**Player:**")) {
        return;
      }

      parseAndMatch(mc, currentClipboard);

    } catch (Exception e) {
      PimClient.LOGGER.debug("[ClipboardParser] clipboard parse failed", e);
    }
  }

  private void parseAndMatch(Minecraft mc, String clipboard) {
    String[] lines = clipboard.split("\n");

    String playerName = extractPlayerName(lines);
    if (playerName == null) {
      return;
    }

    if (playerName.equals(mc.player.getName().getString())) {
      return;
    }

    PinShortNameGenerator shortNameGenerator = PinShortNameGenerator.getInstance();
    shortNameGenerator.generateShortNames();

    Map<String, List<String>> lookingFor = parseSection(lines, ":lookingfor:", shortNameGenerator);
    Map<String, List<String>> forSale = parseSection(lines, ":forsale:", shortNameGenerator);

    Map<String, List<String>> matchesLookingFor = matchInventoryPins(lookingFor);
    Map<String, List<String>> matchesForSale = matchDetailPins(forSale);

    displayResults(playerName, matchesLookingFor, matchesForSale, shortNameGenerator);
  }

  private String extractPlayerName(String[] lines) {
    for (String line : lines) {
      if (line.startsWith("**Player:**")) {
        String playerName = line.replace("**Player:**", "").trim();
        return playerName.isEmpty() ? null : playerName;
      }
    }
    return null;
  }

  private Map<String, List<String>> parseSection(
      String[] lines, String sectionMarker, PinShortNameGenerator shortNameGenerator) {
    Map<String, List<String>> result = new TreeMap<>();

    String forsaleEmoji = "<:forsale:1337590056516456478>";
    String lookingforEmoji = "<:lookingfor:1337529177347194971>";

    boolean inSection = false;

    for (String line : lines) {
      String trimmedLine = line.trim();

      if (trimmedLine.startsWith(":forsale:")
          || trimmedLine.startsWith(forsaleEmoji)
          || trimmedLine.startsWith(":lookingfor:")
          || trimmedLine.startsWith(lookingforEmoji)) {
        inSection =
            trimmedLine.startsWith(sectionMarker)
                || trimmedLine.startsWith(
                    sectionMarker.equals(":forsale:") ? forsaleEmoji : lookingforEmoji);
        continue;
      }

      if (!inSection) {
        continue;
      }

      if (trimmedLine.startsWith(":") || trimmedLine.startsWith("<:")) {
        inSection = false;
        continue;
      }

      if (trimmedLine.startsWith("-")) {
        int colonIndex = trimmedLine.indexOf(':');
        if (colonIndex > 1) {
          String shortSeriesName = trimmedLine.substring(1, colonIndex).trim();
          String actualSeriesName = shortNameGenerator.getSeriesActualName(shortSeriesName);
          if (actualSeriesName == null) {
            actualSeriesName = shortSeriesName;
          }
          String pinsPart = trimmedLine.substring(colonIndex + 1).trim();

          if (!pinsPart.isEmpty()) {
            List<String> pins = new ArrayList<>();
            String[] pinArray = pinsPart.split(" ");
            for (String pin : pinArray) {
              String trimmedPin = pin.trim();
              if (!trimmedPin.isEmpty()) {
                String actualName = shortNameGenerator.getActualName(actualSeriesName, trimmedPin);
                if (actualName != null) {
                  pins.add(actualName);
                }
              }
            }

            if (!pins.isEmpty()) {
              result.put(actualSeriesName, pins);
            }
          }
        }
      }
    }

    return result;
  }

  private Map<String, List<String>> matchInventoryPins(Map<String, List<String>> lookingFor) {
    Map<String, List<String>> matches = new TreeMap<>();

    Set<String> inventoryMintPins = com.chenweikeng.imf.pim.pin.Utils.getPlayerInventoryMintPins();

    for (Map.Entry<String, List<String>> entry : lookingFor.entrySet()) {
      String seriesName = entry.getKey();
      List<String> pins = entry.getValue();

      List<String> matchedPins = new ArrayList<>();

      for (String pinName : pins) {
        String key = seriesName + ":" + pinName;
        if (inventoryMintPins.contains(key)) {
          matchedPins.add(pinName);
        }
      }

      if (!matchedPins.isEmpty()) {
        matches.put(seriesName, matchedPins);
      }
    }

    return matches;
  }

  private Map<String, List<String>> matchDetailPins(Map<String, List<String>> forSale) {
    Map<String, List<String>> matches = new TreeMap<>();

    PinDetailHandler detailHandler = PinDetailHandler.getInstance();
    Set<String> lookingForMintPins = new HashSet<>();

    for (String seriesName : detailHandler.getAllSeriesNames()) {
      Map<String, PinDetailHandler.PinDetailEntry> seriesPins =
          detailHandler.getSeriesDetails(seriesName);
      if (seriesPins != null) {
        for (Map.Entry<String, PinDetailHandler.PinDetailEntry> pinEntry : seriesPins.entrySet()) {
          if (pinEntry.getValue().condition == PinDetailHandler.PinCondition.LOCKED
              || pinEntry.getValue().condition == PinDetailHandler.PinCondition.NOTMINT) {
            lookingForMintPins.add(seriesName + ":" + pinEntry.getKey());
          }
        }
      }
    }

    for (Map.Entry<String, List<String>> entry : forSale.entrySet()) {
      String seriesName = entry.getKey();
      List<String> pins = entry.getValue();

      List<String> matchedPins = new ArrayList<>();

      for (String pinName : pins) {
        String key = seriesName + ":" + pinName;
        if (lookingForMintPins.contains(key)) {
          matchedPins.add(pinName);
        }
      }

      if (!matchedPins.isEmpty()) {
        matches.put(seriesName, matchedPins);
      }
    }

    return matches;
  }

  private void displayResults(
      String playerName,
      Map<String, List<String>> matchesLookingFor,
      Map<String, List<String>> matchesForSale,
      PinShortNameGenerator shortNameGenerator) {
    if (matchesLookingFor.isEmpty() && matchesForSale.isEmpty()) {
      return;
    }

    Minecraft.getInstance()
        .player
        .displayClientMessage(
            Component.literal("§6✨ §e[IMF] §fMatched with §b" + playerName + ":"), false);

    StringBuilder offerText = new StringBuilder();
    StringBuilder takeText = new StringBuilder();

    if (!matchesLookingFor.isEmpty()) {
      Minecraft.getInstance()
          .player
          .displayClientMessage(Component.literal("§6✨ §e[IMF] §aYou can offer to them:"), false);
      for (Map.Entry<String, List<String>> entry : matchesLookingFor.entrySet()) {
        String seriesName = entry.getKey();
        Minecraft.getInstance()
            .player
            .displayClientMessage(
                Component.literal(
                    "§6✨ §e[IMF] §f- §9"
                        + seriesName
                        + ":§f "
                        + String.join(", ", entry.getValue())),
                false);
        offerText
            .append("- ")
            .append(seriesName)
            .append(": ")
            .append(String.join(", ", entry.getValue()))
            .append("\n");
      }
    }

    if (!matchesForSale.isEmpty()) {
      Minecraft.getInstance()
          .player
          .displayClientMessage(Component.literal("§6✨ §e[IMF] §aYou need from them:"), false);
      for (Map.Entry<String, List<String>> entry : matchesForSale.entrySet()) {
        String seriesName = entry.getKey();
        Minecraft.getInstance()
            .player
            .displayClientMessage(
                Component.literal(
                    "§6✨ §e[IMF] §f- §9"
                        + seriesName
                        + ":§f "
                        + String.join(", ", entry.getValue())),
                false);
        takeText
            .append("- ")
            .append(seriesName)
            .append(": ")
            .append(String.join(", ", entry.getValue()))
            .append("\n");
      }
    }

    if (!matchesLookingFor.isEmpty() || !matchesForSale.isEmpty()) {
      StringBuilder exchangeMessage = new StringBuilder();
      if (!matchesLookingFor.isEmpty()) {
        exchangeMessage.append("I want to offer:\n").append(offerText);
      }
      if (!matchesLookingFor.isEmpty() && !matchesForSale.isEmpty()) {
        exchangeMessage.append("\n");
      }
      if (!matchesForSale.isEmpty()) {
        exchangeMessage.append("I want to take:\n").append(takeText);
      }

      try {
        Minecraft.getInstance().keyboardHandler.setClipboard(exchangeMessage.toString());
        Minecraft.getInstance()
            .player
            .displayClientMessage(
                Component.literal("§6✨ §e[IMF] §a✓ Exchange message copied to clipboard!"), false);
      } catch (Exception e) {
        Minecraft.getInstance()
            .player
            .displayClientMessage(
                Component.literal("§6✨ §e[IMF] §c⚠ Failed to copy exchange message"), false);
      }
    }
  }
}
