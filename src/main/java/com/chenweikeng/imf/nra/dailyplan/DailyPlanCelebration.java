package com.chenweikeng.imf.nra.dailyplan;

import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.config.RideHubMode;
import com.chenweikeng.imf.nra.dailyplan.DailyPlanLayer.LayerType;
import com.chenweikeng.imf.nra.ride.RideName;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;

/**
 * Sound + particle flourish for node and layer completions. Chat messages are intentionally not
 * sent — the HUD's colour/state change already signals the completion, so extra chat lines are
 * noise. Plays only in Ride Plan mode; completions still tracked in Strategy Hub mode arrive
 * silently.
 */
public final class DailyPlanCelebration {
  private static final Random RANDOM = new Random();
  private static final int NODE_PARTICLES = 10;
  private static final int LAYER_PARTICLES = 18;

  private DailyPlanCelebration() {}

  public static void nodeCompleted(Minecraft client, RideName ride, int nodeIndex, int siblings) {
    if (client == null || client.player == null || client.level == null) {
      return;
    }
    if (ModConfig.currentSetting.rideHubMode != RideHubMode.RIDE_PLAN) {
      return;
    }
    client
        .getSoundManager()
        .play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.5f, 1.0f));
    spawnParticles(client, NODE_PARTICLES);
  }

  public static void layerCompleted(Minecraft client, int layerNumber, LayerType type) {
    if (client == null || client.player == null || client.level == null) {
      return;
    }
    if (ModConfig.currentSetting.rideHubMode != RideHubMode.RIDE_PLAN) {
      return;
    }
    client
        .getSoundManager()
        .play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 0.9f, 1.3f));
    spawnParticles(client, LAYER_PARTICLES);
  }

  private static void spawnParticles(Minecraft client, int count) {
    double x = client.player.getX();
    double y = client.player.getY() + 1.0;
    double z = client.player.getZ();
    for (int i = 0; i < count; i++) {
      double dx = (RANDOM.nextDouble() - 0.5) * 1.4;
      double dy = RANDOM.nextDouble() * 0.8;
      double dz = (RANDOM.nextDouble() - 0.5) * 1.4;
      client.level.addParticle(ParticleTypes.HAPPY_VILLAGER, x + dx, y + dy, z + dz, 0, 0.1, 0);
    }
  }
}
