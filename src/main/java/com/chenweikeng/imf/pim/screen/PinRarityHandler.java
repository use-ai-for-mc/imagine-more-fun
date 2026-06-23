package com.chenweikeng.imf.pim.screen;

import com.chenweikeng.imf.ImfFileIO;
import com.chenweikeng.imf.pim.PimClient;
import com.chenweikeng.imf.pim.pin.PinPackTier;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

public class PinRarityHandler {
  private static PinRarityHandler instance;
  private final Map<String, PinSeriesEntry> seriesMap;
  private final Gson gson;
  private final File dataFile;
  private int pendingUpdatedCount = 0;
  private boolean hasPendingChanges = false;
  private int tickCount = 0;

  public enum Availability {
    REQUIRED,
    OPTIONAL
  }

  private PinRarityHandler() {
    this.gson = new GsonBuilder().setPrettyPrinting().create();
    this.seriesMap = new HashMap<>();
    this.dataFile = new File("config/pim_pin_rarity.json");
    load();
  }

  public static PinRarityHandler getInstance() {
    if (instance == null) {
      instance = new PinRarityHandler();
    }
    return instance;
  }

  private boolean updateSeriesEntry(PinSeriesEntry parsedEntry) {
    PinSeriesEntry existingEntry = seriesMap.get(parsedEntry.seriesName);
    boolean changed = false;

    if (existingEntry == null) {
      seriesMap.put(parsedEntry.seriesName, parsedEntry);
      changed = true;
    } else {
      if (existingEntry.availability != parsedEntry.availability) {
        existingEntry.availability = parsedEntry.availability;
        changed = true;
      }
      if (existingEntry.color == null && parsedEntry.color != null) {
        existingEntry.color = parsedEntry.color;
        changed = true;
      } else if (existingEntry.color != null
          && parsedEntry.color != null
          && !existingEntry.color.equals(parsedEntry.color)) {
        existingEntry.color = parsedEntry.color;
        changed = true;
      }
    }

    if (changed) {
      pendingUpdatedCount++;
      hasPendingChanges = true;
    }

    return changed;
  }

  private void flushUpdates() {
    if (!hasPendingChanges || pendingUpdatedCount == 0) {
      return;
    }

    var player = Minecraft.getInstance().player;
    if (player != null) {
      player.displayClientMessage(
          Component.literal(
              "§6✨ §e[IMF] §fThe pin rarity information has been updated for §e"
                  + pendingUpdatedCount
                  + "§f pin pack"
                  + (pendingUpdatedCount == 1 ? "" : "s")
                  + ". There "
                  + (seriesMap.size() == 1 ? "is" : "are")
                  + " §e"
                  + seriesMap.size()
                  + "§f pin pack"
                  + (seriesMap.size() == 1 ? "" : "s")
                  + " on record."),
          false);
    }

    save();

    pendingUpdatedCount = 0;
    hasPendingChanges = false;
  }

  public void handleContainerData(ClientboundContainerSetContentPacket packet) {
    List<ItemStack> items = packet.items();

    for (int i = 0; i < Math.min(items.size(), 45); i++) {
      ItemStack stack = items.get(i);
      if (!stack.isEmpty()) {
        PinSeriesEntry parsedEntry = parsePinSeriesEntry(stack);
        if (parsedEntry != null) {
          updateSeriesEntry(parsedEntry);
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

    PinSeriesEntry parsedEntry = parsePinSeriesEntry(itemStack);
    if (parsedEntry == null) {
      return;
    }

    updateSeriesEntry(parsedEntry);
  }

  public PinSeriesEntry getSeriesEntry(String seriesName) {
    return seriesMap.get(seriesName);
  }

  public java.util.Set<String> getAllSeriesNames() {
    return seriesMap.keySet();
  }

  public void tick() {
    tickCount++;
    if (tickCount >= 20) {
      tickCount = 0;
      flushUpdates();
    }
  }

  private void save() {
    ImfFileIO.writeJsonAtomic(
        dataFile.toPath(), gson, seriesMap, PimClient.LOGGER, "PIM pin rarity data");
  }

  private void load() {
    if (!dataFile.exists()) {
      return;
    }

    Type mapType = new TypeToken<Map<String, PinSeriesEntry>>() {}.getType();
    Map<String, PinSeriesEntry> loadedMap =
        ImfFileIO.readJson(
            dataFile.toPath(), gson, mapType, PimClient.LOGGER, "PIM pin rarity data");
    if (loadedMap != null) {
      seriesMap.putAll(loadedMap);
    }
  }

  public void reset() {
    seriesMap.clear();
    pendingUpdatedCount = 0;
    hasPendingChanges = false;

    if (dataFile.exists()) {
      if (dataFile.delete()) {
        PimClient.LOGGER.info("[Pim] Pin rarity data file deleted successfully");
      } else {
        PimClient.LOGGER.warn("[Pim] Failed to delete pin rarity data file");
      }
    }
  }

  private PinSeriesEntry parsePinSeriesEntry(ItemStack stack) {
    DataComponentMap map = stack.getComponents();
    Component customName = map.get(DataComponents.CUSTOM_NAME);
    ItemLore lore = map.get(DataComponents.LORE);

    if (customName == null) {
      return null;
    }

    String name = ChatFormatting.stripFormatting(customName.getString());
    if (!name.startsWith("Pin Pack - ")) {
      return null;
    }

    PinSeriesEntry entry = new PinSeriesEntry();
    entry.seriesName = name.substring("Pin Pack - ".length());
    entry.availability = Availability.OPTIONAL;

    if (lore != null) {
      for (Component line : lore.lines()) {
        String loreText = ChatFormatting.stripFormatting(line.getString());
        if (loreText.contains("Available at pin shops")) {
          entry.availability = Availability.REQUIRED;
        } else if (loreText.contains("No longer sold")) {
          entry.availability = Availability.OPTIONAL;
        }
      }
    }

    List<Component> flatComponents = customName.toFlatList();
    if (!flatComponents.isEmpty()) {
      Component lastComponent = flatComponents.get(flatComponents.size() - 1);
      String colorCode =
          lastComponent.getStyle().getColor() != null
              ? lastComponent.getStyle().getColor().toString()
              : "null";
      PinPackTier parsedColor = PinPackTier.fromString(colorCode);
      entry.color = parsedColor;
    }

    return entry;
  }

  public static class PinSeriesEntry {
    public String seriesName;
    public Availability availability;
    public PinPackTier color;
  }
}
