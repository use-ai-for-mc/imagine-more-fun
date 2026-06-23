package com.chenweikeng.imf.pim.screen;

import com.chenweikeng.imf.ImfFileIO;
import com.chenweikeng.imf.nra.session.SessionTracker;
import com.chenweikeng.imf.pim.PimClient;
import com.chenweikeng.imf.pim.pin.Rarity;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

public class PinDetailHandler {
  private static PinDetailHandler instance;
  private final Map<String, Map<String, PinDetailEntry>> detailMap;
  private final Map<String, Integer> pendingUpdatedCountBySeries;
  private final Gson gson;
  private final File dataFile;
  private boolean hasPendingChanges = false;
  private int tickCount = 0;
  private static String currentOpenedPinSeries;

  public static String getCurrentOpenedPinSeries() {
    return currentOpenedPinSeries;
  }

  public static void setCurrentOpenedPinSeries(String series) {
    currentOpenedPinSeries = series;
  }

  public enum PinCondition {
    LOCKED,
    MINT,
    NOTMINT
  }

  private PinDetailHandler() {
    this.gson = new GsonBuilder().setPrettyPrinting().create();
    this.detailMap = new HashMap<>();
    this.pendingUpdatedCountBySeries = new HashMap<>();
    this.dataFile = new File("config/pim_pin_detail.json");
    load();
  }

  public static PinDetailHandler getInstance() {
    if (instance == null) {
      instance = new PinDetailHandler();
    }
    return instance;
  }

  private boolean updateDetailEntry(PinDetailEntry parsedEntry) {
    if (currentOpenedPinSeries == null) {
      return false;
    }

    Map<String, PinDetailEntry> seriesMap =
        detailMap.computeIfAbsent(currentOpenedPinSeries, k -> new HashMap<>());
    PinDetailEntry existingEntry = seriesMap.get(parsedEntry.pinName);
    boolean added = false;
    boolean updated = false;
    boolean newMintAdded = false;

    if (existingEntry == null) {
      seriesMap.put(parsedEntry.pinName, parsedEntry);
      added = true;
      // Track if this is a new MINT pin being added for the first time
      if (parsedEntry.condition == PinCondition.MINT) {
        newMintAdded = true;
      }
    } else {
      if (existingEntry.condition != parsedEntry.condition) {
        // Track when a pin changes to MINT (was previously LOCKED or NOTMINT)
        if (parsedEntry.condition == PinCondition.MINT
            && existingEntry.condition != PinCondition.MINT) {
          newMintAdded = true;
        }
        existingEntry.condition = parsedEntry.condition;
        updated = true;
      }
      if (parsedEntry.rarity != null && !parsedEntry.rarity.equals(existingEntry.rarity)) {
        existingEntry.rarity = parsedEntry.rarity;
        updated = true;
      }
    }

    if (added) {
      pendingUpdatedCountBySeries.merge(currentOpenedPinSeries, 1, Integer::sum);
    }

    if (newMintAdded) {
      SessionTracker.getInstance().onNewMintPinAdded();
    }

    if (added || updated) {
      hasPendingChanges = true;
    }

    return added || updated;
  }

  private void flushUpdates() {
    if (!hasPendingChanges) {
      return;
    }

    var player = Minecraft.getInstance().player;
    if (player != null) {
      for (Map.Entry<String, Integer> entry : pendingUpdatedCountBySeries.entrySet()) {
        String seriesName = entry.getKey();
        int count = entry.getValue();
        if (count > 0) {
          player.displayClientMessage(
              Component.literal(
                  "§6✨ §e[IMF] §fThe pin detail information has been updated for §e"
                      + count
                      + "§f pin"
                      + (count == 1 ? "" : "s")
                      + " in §e"
                      + seriesName
                      + "§f."),
              false);
        }
      }
    }

    save();

    pendingUpdatedCountBySeries.clear();
    hasPendingChanges = false;
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

    PinDetailEntry parsedEntry = parsePinEntry(itemStack);
    if (parsedEntry == null) {
      return;
    }

    updateDetailEntry(parsedEntry);
  }

  public void handleContainerData(ClientboundContainerSetContentPacket packet) {
    if (packet.containerId() == 0) {
      return;
    }

    List<ItemStack> items = packet.items();

    for (int i = 0; i < Math.min(items.size(), 45); i++) {
      ItemStack stack = items.get(i);
      if (!stack.isEmpty()) {
        PinDetailEntry parsedEntry = parsePinEntry(stack);
        if (parsedEntry != null) {
          updateDetailEntry(parsedEntry);
        }
      }
    }
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
        dataFile.toPath(), gson, detailMap, PimClient.LOGGER, "PIM pin detail data");
  }

  private void load() {
    if (!dataFile.exists()) {
      return;
    }

    Type mapType = new TypeToken<Map<String, Map<String, PinDetailEntry>>>() {}.getType();
    Map<String, Map<String, PinDetailEntry>> loadedMap =
        ImfFileIO.readJson(
            dataFile.toPath(), gson, mapType, PimClient.LOGGER, "PIM pin detail data");
    if (loadedMap != null) {
      detailMap.putAll(loadedMap);
    }
  }

  public static PinDetailEntry parsePinEntry(ItemStack stack) {
    DataComponentMap map = stack.getComponents();
    Component customName = map.get(DataComponents.CUSTOM_NAME);
    ItemLore lore = map.get(DataComponents.LORE);

    if (customName == null || lore == null) {
      return null;
    }

    PinDetailEntry entry = new PinDetailEntry();
    entry.pinName = ChatFormatting.stripFormatting(customName.getString());
    boolean isPinEntry = false;

    for (Component line : lore.lines()) {
      String loreText = ChatFormatting.stripFormatting(line.getString());

      if (loreText.startsWith("Status : ")) {
        String status = loreText.substring("Status : ".length());
        if (status.equals("Locked")) {
          entry.condition = PinCondition.LOCKED;
        }
        isPinEntry = true;
      } else if (loreText.startsWith("Pin Condition: ")) {
        String condition = loreText.substring("Pin Condition: ".length());
        if (condition.equals("Brand New Mint Condition")) {
          entry.condition = PinCondition.MINT;
        } else {
          entry.condition = PinCondition.NOTMINT;
        }
        isPinEntry = true;
      } else if (loreText.startsWith("Pin Rarity: ")) {
        String rarity = loreText.substring("Pin Rarity: ".length());
        entry.rarity = Rarity.fromString(rarity);
      } else if (loreText.startsWith("Rarity : ")) {
        String rarity = loreText.substring("Rarity : ".length());
        entry.rarity = Rarity.fromString(rarity);
      }
    }

    return isPinEntry ? entry : null;
  }

  public Map<String, PinDetailEntry> getSeriesDetails(String seriesName) {
    return detailMap.get(seriesName);
  }

  public Set<String> getAllSeriesNames() {
    return detailMap.keySet();
  }

  public static String parsePinSeriesFromLore(ItemStack stack) {
    DataComponentMap map = stack.getComponents();
    ItemLore lore = map.get(DataComponents.LORE);

    if (lore == null) {
      return null;
    }

    for (Component line : lore.lines()) {
      String loreText = ChatFormatting.stripFormatting(line.getString());
      if (loreText.startsWith("Pin Series: ")) {
        return loreText.substring("Pin Series: ".length());
      }
    }

    return null;
  }

  public PinDetailEntry findDetailEntry(String seriesName, String pinName) {
    Map<String, PinDetailEntry> seriesMap = detailMap.get(seriesName);
    if (seriesMap == null) {
      return null;
    }
    return seriesMap.get(pinName);
  }

  public void reset() {
    detailMap.clear();
    pendingUpdatedCountBySeries.clear();
    hasPendingChanges = false;
    tickCount = 0;
    currentOpenedPinSeries = null;

    if (dataFile.exists()) {
      if (dataFile.delete()) {
        PimClient.LOGGER.info("[Pim] Pin detail data file deleted successfully");
      } else {
        PimClient.LOGGER.warn("[Pim] Failed to delete pin detail data file");
      }
    }
  }

  public static class PinDetailEntry {
    public String pinName;
    public PinCondition condition;
    public Rarity rarity;
  }
}
