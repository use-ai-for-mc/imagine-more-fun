package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.ride.RideCountManager;
import com.chenweikeng.imf.nra.ride.RideName;
import com.chenweikeng.imf.nra.ride.RideStatsSourceCoordinator;
import com.chenweikeng.imf.nra.strategy.StrategyHudRendererDispatcher;
import com.chenweikeng.imf.nra.tracker.OtherPlayerStatsTracker;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class NraClientPacketListenerMixin {
  private static final Pattern TOTAL_RIDES_PATTERN =
      Pattern.compile("Total rides:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

  @Inject(at = @At("HEAD"), method = "handleContainerContent")
  public void onInventory(
      ClientboundContainerSetContentPacket inventoryS2CPacket, CallbackInfo ci) {
    if (!NotRidingAlertClient.isImagineFunServer()) {
      return;
    }
    if (!OtherPlayerStatsTracker.getInstance().isRideStatsActive()) {
      return;
    }
    if (!RideStatsSourceCoordinator.shouldCaptureLegacyRideStats()) {
      return;
    }
    var player = Minecraft.getInstance().player;
    if (player != null) {
      List<ItemStack> itemStacks = inventoryS2CPacket.items();

      // Check if we have at least 4 items (indices 0-3)
      if (itemStacks.size() < 4) {
        return;
      }

      // Check if 2nd item (index 1) has CUSTOM_NAME "Disneyland"
      Component secondItemName = itemStacks.get(1).getComponents().get(DataComponents.CUSTOM_NAME);
      if (secondItemName == null || !"Disneyland".equals(secondItemName.getString())) {
        return;
      }

      // Check if 3rd item (index 2) has CUSTOM_NAME "DCA"
      Component thirdItemName = itemStacks.get(2).getComponents().get(DataComponents.CUSTOM_NAME);
      if (thirdItemName == null || !"DCA".equals(thirdItemName.getString())) {
        return;
      }

      // Check if 4th item (index 3) has CUSTOM_NAME "Retro"
      Component fourthItemName = itemStacks.get(3).getComponents().get(DataComponents.CUSTOM_NAME);
      if (fourthItemName == null || !"Retro".equals(fourthItemName.getString())) {
        return;
      }

      // All conditions met, now process items
      RideCountManager countManager = RideCountManager.getInstance();
      for (ItemStack itemStack : itemStacks) {
        // Get CUSTOM_NAME
        Component customName = itemStack.getComponents().get(DataComponents.CUSTOM_NAME);

        // Skip if CUSTOM_NAME is null
        if (customName == null) {
          continue;
        }

        String nameString = customName.getString();

        // Skip if CUSTOM_NAME is "Overall"
        if ("Overall".equals(nameString)) {
          continue;
        }

        // Get LORE
        ItemLore itemLore = itemStack.getComponents().get(DataComponents.LORE);
        if (itemLore != null) {
          List<Component> lines = itemLore.lines();
          // Check if we have at least 2 lines (indices 0 and 1)
          if (lines.size() >= 2) {
            String secondLine = lines.get(1).getString();
            // Try to extract number from "Total rides: [NUM]"
            Matcher matcher = TOTAL_RIDES_PATTERN.matcher(secondLine);
            if (matcher.find()) {
              try {
                int count = Integer.parseInt(matcher.group(1));
                RideName ride = RideName.fromMatchString(nameString);
                countManager.updateRideCount(ride, count);
              } catch (NumberFormatException e) {
                // Ignore invalid numbers
              }
            }
          }
        }
      }
    }
  }

  @Inject(at = @At("HEAD"), method = "handleContainerSetSlot")
  public void onScreenHandlerSlotUpdate(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
    if (!NotRidingAlertClient.isImagineFunServer()) {
      return;
    }
    if (!OtherPlayerStatsTracker.getInstance().isRideStatsActive()) {
      return;
    }
    if (!RideStatsSourceCoordinator.shouldCaptureLegacyRideStats()) {
      return;
    }
    var player = Minecraft.getInstance().player;
    if (player != null) {
      // ScreenHandlerSlotUpdateS2CPacket contains a single ItemStack (stack field)
      ItemStack itemStack = packet.getItem();
      if (itemStack == null || itemStack.isEmpty()) {
        return;
      }

      // Get CUSTOM_NAME
      Component customName = itemStack.getComponents().get(DataComponents.CUSTOM_NAME);

      // Skip if CUSTOM_NAME is null
      if (customName == null) {
        return;
      }

      String nameString = customName.getString();

      // Skip if CUSTOM_NAME is "Overall"
      if ("Overall".equals(nameString)) {
        return;
      }

      // Get LORE
      ItemLore itemLore = itemStack.getComponents().get(DataComponents.LORE);
      if (itemLore != null) {
        List<Component> lines = itemLore.lines();
        // Check if we have more than 2 lines
        if (lines.size() > 2) {
          // Check if 2nd line (index 1) matches the total rides pattern
          String secondLine = lines.get(1).getString();
          Matcher matcher = TOTAL_RIDES_PATTERN.matcher(secondLine);
          if (matcher.find()) {
            try {
              int count = Integer.parseInt(matcher.group(1));
              RideName ride = RideName.fromMatchString(nameString);
              if (ride == RideName.UNKNOWN) {
                StrategyHudRendererDispatcher.setError(
                    "Unknown ride detected (name: " + nameString + ")");
              }
              RideCountManager countManager = RideCountManager.getInstance();
              countManager.updateRideCount(ride, count);
            } catch (NumberFormatException e) {
              // Ignore invalid numbers
            }
          }
        }
      }
    }
  }
}
