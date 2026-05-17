package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.dailyplan.DailyPlanManager;
import com.chenweikeng.imf.nra.dailyplan.DailyQuest;
import com.chenweikeng.imf.nra.dailyplan.DailyQuestParser;
import com.chenweikeng.imf.nra.dailyplan.DailyQuestSnapshot;
import com.chenweikeng.imf.nra.dailyplan.DailyQuestState;
import com.chenweikeng.imf.nra.ride.RideCountManager;
import com.chenweikeng.imf.nra.ride.RideName;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the player's pending daily quests off the server's Daily Objectives chest GUI. Gated on
 * the same {@code 丳} title-marker the alphatable mixin uses, so we only fire on the Quest window,
 * then narrowed to the Daily Objectives tab via slot 0's "Buy Daily Refresh" button (the Quests /
 * Weekly Challenges tabs share the title marker but populate slot 0 differently).
 *
 * <p>The screen ships with empty slots on the very first tick after open, so capture runs from
 * {@code containerTick} and idles once a parse has succeeded for this screen instance — including
 * the legitimate "no quests" parse, where slots 9–16 are all empty because the server retired the
 * completed quest. Quest items live as {@code minecraft:diamond_shovel} stacks in slots 9–16, with
 * the actual quest text in their {@link DataComponents#LORE LORE} component.
 */
@Mixin(AbstractContainerScreen.class)
public class ImfDailyObjectivesScreenMixin {

  private static final String TITLE_MARKER = "丳";
  private static final int REFRESH_SLOT = 0;
  private static final String REFRESH_SLOT_NAME = "Buy Daily Refresh";
  private static final int FIRST_QUEST_SLOT = 9;
  private static final int LAST_QUEST_SLOT = 16;

  @Unique private boolean imf$dailyQuestCaptured;

  @Inject(at = @At("TAIL"), method = "containerTick")
  public void imf$captureDailyQuests(CallbackInfo ci) {
    if (imf$dailyQuestCaptured) {
      return;
    }
    AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
    Component title = self.getTitle();
    if (title == null || !title.getString().contains(TITLE_MARKER)) {
      return;
    }
    AbstractContainerMenu menu = self.getMenu();
    if (menu == null) {
      return;
    }
    int slotCount = menu.slots.size();
    if (slotCount <= LAST_QUEST_SLOT) {
      return;
    }

    // Tab + loaded gate: slot 0 holds "Buy Daily Refresh" only on the Daily Objectives tab and
    // only after the chest packet has fully landed. Earlier we used "any diamond_shovel in 9–17"
    // as the gate, but that misfires when all quests are done (server removes them entirely) —
    // we'd then never overwrite the snapshot, so reconcile never flipped the pinned layer.
    Slot refreshSlot = menu.slots.get(REFRESH_SLOT);
    if (refreshSlot == null) {
      return;
    }
    ItemStack refreshStack = refreshSlot.getItem();
    if (refreshStack.isEmpty() || !refreshStack.is(Items.CHORUS_FRUIT)) {
      return;
    }
    if (!REFRESH_SLOT_NAME.equals(refreshStack.getHoverName().getString())) {
      return;
    }

    List<DailyQuest> parsed = new ArrayList<>();
    for (int idx = FIRST_QUEST_SLOT; idx <= LAST_QUEST_SLOT; idx++) {
      Slot slot = menu.slots.get(idx);
      if (slot == null) {
        continue;
      }
      ItemStack stack = slot.getItem();
      if (stack.isEmpty() || !stack.is(Items.DIAMOND_SHOVEL)) {
        continue;
      }
      ItemLore lore = stack.get(DataComponents.LORE);
      if (lore == null) {
        continue;
      }
      List<String> raw = new ArrayList<>();
      for (Component line : lore.lines()) {
        raw.add(line.getString());
      }
      Optional<DailyQuest> quest = DailyQuestParser.parse(raw);
      quest.ifPresent(parsed::add);
    }

    // parsed may be empty — that's the "all quests completed" state, and snapshotting it is what
    // drives reconcileSpecialQuestLayers to flip pinned :riddle: / :npc: / :land: / :task: layers
    // to completed (their match disappears from the fresh snapshot).
    DailyQuestSnapshot snap = new DailyQuestSnapshot();
    snap.capturedAtEpochMs = System.currentTimeMillis();
    snap.capturedDate = LocalDate.now().toString();
    snap.quests = parsed;
    RideCountManager counts = RideCountManager.getInstance();
    for (DailyQuest q : parsed) {
      if (q.kindOrDefault() != DailyQuest.Kind.RIDE) {
        // RIDDLE_RIDE / NPC don't track ride counts; their pin-key is a sentinel, not a real ride.
        continue;
      }
      RideName ride = RideName.fromMatchString(q.rideMatchName);
      snap.rideCountsAtCapture.put(q.rideMatchName, counts.getRideCount(ride));
    }
    DailyQuestState.getInstance().setSnapshot(snap);
    DailyPlanManager.getInstance()
        .reconcileSpecialQuestLayers(net.minecraft.client.Minecraft.getInstance(), snap);
    DailyPlanManager.getInstance().pruneStalePriorRideQuestLayers(snap);
    DailyPlanManager.getInstance().injectPendingQuestLayers();
    imf$dailyQuestCaptured = true;
  }
}
