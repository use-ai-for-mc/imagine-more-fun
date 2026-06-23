package com.chenweikeng.imf.pim.screen;

import com.chenweikeng.imf.ImfFileIO;
import com.chenweikeng.imf.pim.PimClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

public class PinBookHandler {
  private static PinBookHandler instance;
  private final Map<String, PinBookEntry> bookMap;
  private final List<String> pendingMissingSeries;
  private final List<String> pendingNewSeries;
  private final Gson gson;
  private final File dataFile;
  private int tickCount;

  private PinBookHandler() {
    this.gson = new GsonBuilder().setPrettyPrinting().create();
    this.bookMap = new HashMap<>();
    this.pendingMissingSeries = new ArrayList<>();
    this.pendingNewSeries = new ArrayList<>();
    this.tickCount = 0;
    this.dataFile = new File("config/pim_pin_book.json");
    load();
  }

  public static PinBookHandler getInstance() {
    if (instance == null) {
      instance = new PinBookHandler();
    }
    return instance;
  }

  private boolean updateBookEntry(PinBookEntry parsedEntry) {
    PinBookEntry existingEntry = bookMap.get(parsedEntry.seriesName);
    boolean isNew = false;

    if (existingEntry == null) {
      bookMap.put(parsedEntry.seriesName, parsedEntry);
      isNew = true;
      pendingNewSeries.add(parsedEntry.seriesName);
    } else {
      existingEntry.mintsCollected = parsedEntry.mintsCollected;
      existingEntry.totalMints = parsedEntry.totalMints;
    }

    PinRarityHandler.PinSeriesEntry seriesEntry =
        PinRarityHandler.getInstance().getSeriesEntry(parsedEntry.seriesName);
    if (seriesEntry == null) {
      if (!pendingMissingSeries.contains(parsedEntry.seriesName)) {
        pendingMissingSeries.add(parsedEntry.seriesName);
      }
    }

    return isNew;
  }

  public void handleContainerData(ClientboundContainerSetContentPacket packet) {
    if (packet.containerId() == 0) {
      return;
    }
    List<ItemStack> items = packet.items();

    for (int i = 0; i < Math.min(items.size(), 45); i++) {
      ItemStack stack = items.get(i);
      if (!stack.isEmpty()) {
        PinBookEntry parsedEntry = parsePinbookEntry(stack);
        if (parsedEntry != null) {
          updateBookEntry(parsedEntry);
        }
      }
    }
  }

  public void handleContainerSetSlotData(ClientboundContainerSetSlotPacket packet) {
    if (packet.getContainerId() == 0) {
      return;
    }
    if (packet.getSlot() >= 45) {
      return;
    }

    ItemStack itemStack = packet.getItem();
    if (itemStack == null || itemStack.isEmpty()) {
      return;
    }

    PinBookEntry parsedEntry = parsePinbookEntry(itemStack);
    if (parsedEntry == null) {
      return;
    }

    updateBookEntry(parsedEntry);
  }

  public void tick() {
    tickCount++;
    if (tickCount >= 20) {
      tickCount = 0;
      flushMessages();
    }
  }

  private void flushMessages() {
    var player = Minecraft.getInstance().player;
    if (player != null) {
      if (!pendingMissingSeries.isEmpty()) {
        player.displayClientMessage(
            Component.literal(
                "§c⚠ §e[IMF] §fSome required pin series information is missing. Please open"
                    + " §e/pinrarity §fto update the pin series information."),
            false);
      }

      if (!pendingNewSeries.isEmpty()) {
        player.displayClientMessage(
            Component.literal(
                "§6✨ §e[IMF] §fFound §e"
                    + pendingNewSeries.size()
                    + "§f new pin pack"
                    + (pendingNewSeries.size() == 1 ? "" : "s")
                    + " in your pin book."),
            false);
      }
    }

    if (!pendingNewSeries.isEmpty() || !pendingMissingSeries.isEmpty()) {
      save();
    }

    pendingNewSeries.clear();
    pendingMissingSeries.clear();
  }

  private void save() {
    ImfFileIO.writeJsonAtomic(
        dataFile.toPath(), gson, bookMap, PimClient.LOGGER, "PIM pin book data");
  }

  private void load() {
    if (!dataFile.exists()) {
      return;
    }

    Type mapType = new TypeToken<Map<String, PinBookEntry>>() {}.getType();
    Map<String, PinBookEntry> loadedMap =
        ImfFileIO.readJson(dataFile.toPath(), gson, mapType, PimClient.LOGGER, "PIM pin book data");
    if (loadedMap != null) {
      bookMap.putAll(loadedMap);
    }
  }

  public PinBookEntry parsePinbookEntry(ItemStack stack) {
    DataComponentMap map = stack.getComponents();
    Component customName = map.get(DataComponents.CUSTOM_NAME);
    ItemLore lore = map.get(DataComponents.LORE);

    if (customName == null || lore == null) {
      return null;
    }

    String name = ChatFormatting.stripFormatting(customName.getString());
    if (!name.startsWith("Pin Pack - ")) {
      return null;
    }

    PinBookEntry entry = new PinBookEntry();
    entry.seriesName = name.substring("Pin Pack - ".length());

    Pattern mintPattern = Pattern.compile("(\\d+)/(\\d+) mints collected\\.");
    for (Component line : lore.lines()) {
      String loreText = line.getString();
      Matcher matcher = mintPattern.matcher(loreText);
      if (matcher.find()) {
        entry.mintsCollected = Integer.parseInt(matcher.group(1));
        entry.totalMints = Integer.parseInt(matcher.group(2));
        return entry;
      }
    }

    return null;
  }

  public PinBookEntry getBookEntry(String seriesName) {
    return bookMap.get(seriesName);
  }

  public void reset() {
    bookMap.clear();
    pendingNewSeries.clear();
    pendingMissingSeries.clear();
    tickCount = 0;

    if (dataFile.exists()) {
      if (dataFile.delete()) {
        PimClient.LOGGER.info("[Pim] Pin book data file deleted successfully");
      } else {
        PimClient.LOGGER.warn("[Pim] Failed to delete pin book data file");
      }
    }
  }

  public static class PinBookEntry {
    public String seriesName;
    public int mintsCollected;
    public int totalMints;
  }
}
