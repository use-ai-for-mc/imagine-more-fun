package com.chenweikeng.imf.mixin;

import java.util.Map;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPacketListener.class)
public interface NraClientPacketListenerAccessor {
  @Accessor("playerInfoMap")
  Map<UUID, PlayerInfo> getPlayerInfoMap();
}
