package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.quest.QuestCollectibleGlow;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Identifies quest props even when distance or particle settings suppress particle creation. */
@Mixin(ClientPacketListener.class)
public class ImfQuestParticlePacketMixin {
  // RETURN runs after vanilla's client-thread handoff; HEAD also runs on the network thread.
  @Inject(method = "handleParticleEvent", at = @At("RETURN"))
  private void imf$observeQuestDust(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
    QuestCollectibleGlow.observeDust(
        packet.getParticle(), packet.getX(), packet.getY(), packet.getZ());
  }
}
