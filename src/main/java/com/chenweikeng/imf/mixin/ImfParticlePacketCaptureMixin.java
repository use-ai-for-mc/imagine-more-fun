package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.debug.DustParticlePacketCapture;
import com.chenweikeng.imf.nra.quest.QuestCollectibleGlow;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds the opt-in DebugBridge dust probe before packet parameters are expanded and discarded. */
@Mixin(ClientPacketListener.class)
public class ImfParticlePacketCaptureMixin {
  @Inject(method = "handleParticleEvent", at = @At("HEAD"))
  private void imf$captureDustPacket(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
    DustParticlePacketCapture.capture(packet);
    QuestCollectibleGlow.observeDust(
        packet.getParticle(), packet.getX(), packet.getY(), packet.getZ());
  }
}
