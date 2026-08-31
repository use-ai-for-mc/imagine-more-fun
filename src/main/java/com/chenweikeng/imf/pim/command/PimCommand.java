package com.chenweikeng.imf.pim.command;

import com.chenweikeng.imf.pim.PimClient;
import com.chenweikeng.imf.pim.ui.PimScreen;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;

public class PimCommand {

  public static void registerCommands() {
    ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, registryAccess) -> {
          // Register main /pim command that opens GUI
          dispatcher.register(
              ClientCommands.literal("pim")
                  .requires(src -> PimClient.isImagineFunServer())
                  .executes(
                      context -> {
                        Minecraft client = Minecraft.getInstance();
                        client.execute(
                            () -> client.setScreenAndShow(new PimScreen(client.gui.screen())));
                        return 1;
                      }));
        });
  }
}
