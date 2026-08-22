package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.tracker.OtherPlayerStatsTracker;
import com.chenweikeng.imf.nra.wizard.WizardScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class NraMinecraftScreenMixin {
  @Shadow private Screen screen;

  @Shadow
  public abstract void setScreen(@Nullable Screen screen);

  @Unique private boolean nra$wizardFlag = false;

  @Unique private WizardScreen nra$savedWizardScreen = null;

  @Inject(method = "setScreen", at = @At("HEAD"))
  private void nra$onSetScreenHead(Screen newScreen, CallbackInfo ci) {
    if (screen instanceof WizardScreen && newScreen != null && nra$isContainerScreen(newScreen)) {
      nra$wizardFlag = true;
      nra$savedWizardScreen = (WizardScreen) screen;
    } else if (nra$wizardFlag && newScreen != null && !nra$isContainerScreen(newScreen)) {
      nra$wizardFlag = false;
      nra$savedWizardScreen = null;
    } else if (newScreen == null && screen instanceof WizardScreen) {
      nra$wizardFlag = false;
      nra$savedWizardScreen = null;
    }

    if (newScreen == null
        && screen instanceof AbstractContainerScreen
        && OtherPlayerStatsTracker.getInstance().isRideStatsActive()) {
      OtherPlayerStatsTracker.getInstance().setRideStatsActive(false);
    }
  }

  @Inject(method = "setScreen", at = @At("RETURN"))
  private void nra$onSetScreenReturn(Screen newScreen, CallbackInfo ci) {
    if (newScreen == null && nra$wizardFlag && nra$savedWizardScreen != null) {
      setScreen(nra$savedWizardScreen);
    }
  }

  @Unique
  private boolean nra$isContainerScreen(Screen screen) {
    return screen instanceof AbstractContainerScreen;
  }
}
