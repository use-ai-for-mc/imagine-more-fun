package com.chenweikeng.imf.nra.handler;

import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;

/** Accesses the 26.2 clock-backed client day time. */
final class ClientDayTime {
  private ClientDayTime() {}

  static long get(ClientLevel level) {
    return level.clockManager().getTotalTicks(overworldClock(level));
  }

  static void set(ClientLevel level, long ticks) {
    Holder<WorldClock> clock = overworldClock(level);
    level
        .clockManager()
        .handleUpdates(
            level.getGameTime(), Map.of(clock, new ClockNetworkState(ticks, 0.0F, 1.0F)));
    level.environmentAttributes().invalidateTickCache();
  }

  private static Holder<WorldClock> overworldClock(ClientLevel level) {
    return level
        .registryAccess()
        .lookupOrThrow(Registries.WORLD_CLOCK)
        .getOrThrow(WorldClocks.OVERWORLD);
  }
}
