package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.pim.PimClient;
import com.chenweikeng.imf.pim.tracker.BossBarTracker;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BossHealthOverlay.class)
public class PimBossHealthOverlayMixin {
  // Pin Trader's SHA-256 UUID (generated with Python):
  //   import hashlib, uuid
  //   name = 'Pin Trader'
  //   namespace = uuid.UUID('6ba7b810-9dad-11d1-80b4-00c04fd430c8')
  //   data = namespace.bytes + name.encode('utf-8')
  //   sha256 = hashlib.sha256(data).digest()
  //   uuid_bytes = sha256[:16]
  //   uuid_bytes = bytearray(uuid_bytes)
  //   uuid_bytes[6] = (uuid_bytes[6] & 0x0F) | 0x50
  //   uuid_bytes[8] = (uuid_bytes[8] & 0x3F) | 0x80
  //   print(uuid.UUID(bytes=uuid_bytes))
  @Unique
  private static final UUID PIN_TRADER_BOSS_ID =
      UUID.fromString("aefee8e1-9557-5602-811d-f9ae0f4b4303");

  @Unique private LerpingBossEvent pimBossEvent;

  @Shadow private Map<UUID, LerpingBossEvent> events;

  @Inject(method = "<init>", at = @At("RETURN"))
  private void imf$init(CallbackInfo ci) {
    pimBossEvent =
        new LerpingBossEvent(
            PIN_TRADER_BOSS_ID,
            Component.empty(),
            1.0f,
            BossEvent.BossBarColor.PINK,
            BossEvent.BossBarOverlay.PROGRESS,
            false,
            false,
            false);
  }

  @Inject(method = "render", at = @At("HEAD"))
  private void imf$render(GuiGraphics guiGraphics, CallbackInfo ci) {
    if (!PimClient.isImagineFunServer()) {
      return;
    }

    if (BossBarTracker.getInstance().isEnabled()) {
      Component displayTitle = BossBarTracker.getInstance().getDisplayTitle();
      if (!displayTitle.getString().isEmpty()) {
        pimBossEvent.setName(displayTitle);
        pimBossEvent.setProgress(1.0f);
        events.put(PIN_TRADER_BOSS_ID, pimBossEvent);
      } else {
        events.remove(PIN_TRADER_BOSS_ID);
      }
    } else {
      events.remove(PIN_TRADER_BOSS_ID);
    }
  }
}
