package com.chenweikeng.imf.nra.handler;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.strategy.RideGoal;
import com.chenweikeng.imf.nra.strategy.StrategyCalculator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class RandomRideHandler {
  private RandomRideHandler() {}

  /**
   * Intercepts a bare /randomride when the client has ongoing goals. Returns true if the caller
   * should cancel the original command (we dispatched a /w instead); false to let the server handle
   * /randomride.
   */
  public static boolean tryIntercept() {
    if (!NotRidingAlertClient.isImagineFunServer()) {
      return false;
    }
    if (!ModConfig.currentSetting.randomRideOverride) {
      return false;
    }
    Minecraft client = Minecraft.getInstance();
    if (client.player == null || client.player.connection == null) {
      return false;
    }

    List<RideGoal> goals = StrategyCalculator.getTopGoals(Integer.MAX_VALUE);
    if (goals.isEmpty()) {
      return false;
    }

    RideGoal chosen = goals.get(ThreadLocalRandom.current().nextInt(goals.size()));
    String shortName = chosen.getRide().getShortName();

    Component msg =
        Component.empty()
            .append(Component.literal("\u2728 ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal("[IMF] ").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal("Random ride \u2192 ").withStyle(ChatFormatting.WHITE))
            .append(
                Component.literal(chosen.getRide().getDisplayName()).withStyle(ChatFormatting.AQUA))
            .append(
                Component.literal(
                        " (" + chosen.getCurrentCount() + "/" + chosen.getNextGoal() + ")")
                    .withStyle(ChatFormatting.GRAY));
    client.player.sendSystemMessage(msg);

    client.player.connection.sendCommand("w " + shortName);
    return true;
  }
}
