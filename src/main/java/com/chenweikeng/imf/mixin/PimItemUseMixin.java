package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.tracker.FoodConsumptionTracker;
import com.chenweikeng.imf.pim.PimClient;
import com.chenweikeng.imf.pim.PimState;
import com.chenweikeng.imf.pim.tracker.BossBarTracker;
import com.chenweikeng.imf.pim.trader.PinTrader;
import com.chenweikeng.imf.pim.trader.PinTraderRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class PimItemUseMixin {
  private static final long COOLDOWN_MS = 5000;
  private static final long POST_DISABLE_COOLDOWN_MS = 3000;
  private static long lastTriggerTime = 0;
  private static long pimDisabledTime = 0;

  @Inject(at = @At("HEAD"), method = "startUseItem", cancellable = true)
  public void imf$onStartUseItem(CallbackInfo ci) {
    PimClient.LOGGER.debug("[PimItemUseMixin] startUseItem called");

    if (!PimClient.isImagineFunServer()) {
      PimClient.LOGGER.debug("[PimItemUseMixin] Not on ImagineFun server");
      return;
    }

    var player = Minecraft.getInstance().player;

    if (player == null) {
      return;
    }

    ItemStack mainHandItem = player.getMainHandItem();
    ItemStack offHandItem = player.getOffhandItem();

    PimClient.LOGGER.debug(
        "[PimItemUseMixin] mainHand: {}, isTrackableFood: {}",
        mainHandItem.getHoverName().getString(),
        FoodConsumptionTracker.isTrackableFood(mainHandItem));

    if (imf$checkIFoneAndHandle(mainHandItem) || imf$checkIFoneAndHandle(offHandItem)) {
      ci.cancel();
      return;
    }

    // Track food consumption for items with FOOD_TYPE NBT
    int selectedSlot = player.getInventory().getSelectedSlot();
    if (FoodConsumptionTracker.isTrackableFood(mainHandItem)) {
      PimClient.LOGGER.debug(
          "[PimItemUseMixin] Tracking food: {}", mainHandItem.getHoverName().getString());
      FoodConsumptionTracker.getInstance().onItemUseStart(mainHandItem, selectedSlot);
    } else if (FoodConsumptionTracker.isTrackableFood(offHandItem)) {
      // Offhand slot is 40
      PimClient.LOGGER.debug(
          "[PimItemUseMixin] Tracking offhand food: {}", offHandItem.getHoverName().getString());
      FoodConsumptionTracker.getInstance().onItemUseStart(offHandItem, 40);
    }
  }

  @Inject(at = @At("HEAD"), method = "startAttack", cancellable = true)
  public void imf$onStartAttack(CallbackInfoReturnable<Boolean> ci) {
    if (!PimClient.isImagineFunServer()) {
      return;
    }

    var player = Minecraft.getInstance().player;

    if (player == null) {
      return;
    }

    ItemStack mainHandItem = player.getMainHandItem();
    ItemStack offHandItem = player.getOffhandItem();

    if (imf$checkIFoneAndHandle(mainHandItem) || imf$checkIFoneAndHandle(offHandItem)) {
      ci.setReturnValue(false);
    }
  }

  private boolean imf$checkIFoneAndHandle(ItemStack itemStack) {
    String displayName = itemStack.getDisplayName().getString();
    String registryName = BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString();

    if (!"[IFone (Right Click)]".equals(displayName)
        || (!"minecraft:iron_axe".equals(registryName)
            && !"minecraft:netherite_sword".equals(registryName))) {
      return false;
    }

    if (PimState.isEnabled()) {
      long currentTime = System.currentTimeMillis();
      long timeSinceLastTrigger = currentTime - lastTriggerTime;

      if (timeSinceLastTrigger >= COOLDOWN_MS) {
        lastTriggerTime = currentTime;
        imf$handlePimWarp();
      }
      return true;
    }

    long currentTime = System.currentTimeMillis();
    if (currentTime - pimDisabledTime < POST_DISABLE_COOLDOWN_MS) {
      return true;
    }

    return false;
  }

  private void imf$handlePimWarp() {
    if (!PimState.hasNextWarpPoint()) {
      LocalPlayer player = Minecraft.getInstance().player;

      if (player != null) {
        player.connection.sendCommand("w pin");
        player.displayClientMessage(
            Component.literal(
                "§6✨ §e[IMF] §fAll pin traders visited! Returning to Westward Ho Trading Company."),
            false);
        BossBarTracker.getInstance().disable();
        PimState.setEnabled(false);
        PimState.resetWarpPoint();
        pimDisabledTime = System.currentTimeMillis();
      }
      return;
    }

    String warpPoint = PimState.getCurrentWarpPoint();
    LocalPlayer player = Minecraft.getInstance().player;

    if (player != null && warpPoint != null) {
      PinTrader trader =
          PinTraderRegistry.getInstance().getTraderByPosition(PimState.getCurrentTraderPosition());
      String locationName = trader != null ? trader.getLocation().getName() : warpPoint;

      PimState.setActiveWarpPoint(warpPoint);
      player.connection.sendCommand("w " + warpPoint);
      player.displayClientMessage(
          Component.literal("§b➜ §e[IMF] §fWarping to §e" + locationName), false);
      PimClient.LOGGER.info("Pim: Warping to " + warpPoint);
      BossBarTracker.getInstance().enable();
      PimState.incrementWarpPoint();
    }
  }
}
