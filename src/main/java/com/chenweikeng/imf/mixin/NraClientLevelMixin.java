package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.GameState;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.wizard.WizardActionHandler;
import com.chenweikeng.imf.nra.wizard.WizardScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public class NraClientLevelMixin {
  private static final Identifier RIDE_COMPLETE_SOUND =
      Identifier.fromNamespaceAndPath("minecraft", "ride.complete");

  @Inject(method = "playSound", at = @At("HEAD"), cancellable = true)
  private void imf$onPlaySound(
      double d,
      double e,
      double f,
      SoundEvent soundEvent,
      SoundSource soundSource,
      float g,
      float h,
      boolean bl,
      long l,
      CallbackInfo ci) {
    if (!NotRidingAlertClient.isImagineFunServer()) {
      return;
    }

    if (soundEvent != null) {
      Identifier soundId = soundEvent.location();

      // Don't suppress the ride complete sound
      if (soundId != null && soundId.equals(RIDE_COMPLETE_SOUND)) {
        return; // Let the ride complete sound play
      }

      // Suppress other sounds when riding
      if (ModConfig.currentSetting.silent) {
        Minecraft client = Minecraft.getInstance();

        if ((client.screen instanceof WizardScreen)
            && WizardActionHandler.currentlyPlaying != null
            && soundId.equals(WizardActionHandler.currentlyPlaying)) {
          return; // Don't suppress sounds when the wizard is open and indicates this sound should
          // play
        }

        // Check if player is riding (excluding autograb region check via isRiding helper)
        if (GameState.getInstance().isRiding()
            && !GameState.getInstance().isAutograbFailureActive()) {
          // Cancel sound when riding (except ride.complete)
          ci.cancel();
        }
      }
    }
  }
}
