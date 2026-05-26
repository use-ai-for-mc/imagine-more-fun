package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.GameState;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class NraCommandMixin {
  @Inject(method = "sendCommand", at = @At("HEAD"))
  private void imf$onSendCommand(String command, CallbackInfo ci) {
    if (command.equals("sit")) {
      GameState.getInstance().setLastSitCommand();
    }
  }
}
