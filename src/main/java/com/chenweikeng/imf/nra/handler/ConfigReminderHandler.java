package com.chenweikeng.imf.nra.handler;

import com.chenweikeng.imf.nra.ServerState;
import com.chenweikeng.imf.nra.wizard.TutorialManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

public class ConfigReminderHandler {
  private static final int INITIAL_DELAY_TICKS = 600;
  private static final int REMINDER_INTERVAL_TICKS = 12000;

  private boolean hasShownInitialReminder = false;
  private long lastReminderTick = 0;

  public void track(Minecraft client, long currentTick) {
    if (!ServerState.isImagineFunServer()) {
      return;
    }

    if (TutorialManager.getInstance().isCompletedForCurrentTutorial()) {
      return;
    }

    if (client.player == null) {
      return;
    }

    if (!hasShownInitialReminder) {
      if (currentTick >= INITIAL_DELAY_TICKS) {
        sendReminder(client);
        hasShownInitialReminder = true;
        lastReminderTick = currentTick;
      }
    } else {
      if (currentTick - lastReminderTick >= REMINDER_INTERVAL_TICKS) {
        sendReminder(client);
        lastReminderTick = currentTick;
      }
    }
  }

  private void sendReminder(Minecraft client) {
    if (client.player == null) {
      return;
    }

    Component message =
        Component.literal(
                "§6✨ §e[IMF] §fThis mod modifies your gameplay experience significantly. To set up the mod, please run ")
            .append(
                Component.literal("§b/imf setup")
                    .withStyle(
                        s ->
                            s.withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand("imf setup"))))
            .append(Component.literal("§f to open the setup wizard."));

    client.player.sendSystemMessage(message);
  }

  public void reset() {
    hasShownInitialReminder = false;
    lastReminderTick = 0;
  }
}
