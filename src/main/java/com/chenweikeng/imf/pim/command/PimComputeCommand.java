package com.chenweikeng.imf.pim.command;

import com.chenweikeng.imf.pim.PimClient;
import com.chenweikeng.imf.pim.pin.Algorithm;
import com.chenweikeng.imf.pim.pin.Algorithm.DPResult;
import com.chenweikeng.imf.pim.pin.PinCalculationUtils;
import com.chenweikeng.imf.pim.screen.PinBookHandler;
import com.chenweikeng.imf.pim.screen.PinDetailHandler;
import com.chenweikeng.imf.pim.screen.PinRarityHandler;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class PimComputeCommand {

  private static final ExecutorService calculationExecutor =
      Executors.newSingleThreadExecutor(
          new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            private final ClassLoader contextClassLoader =
                Thread.currentThread().getContextClassLoader();

            @Override
            public Thread newThread(Runnable r) {
              Thread thread = new Thread(r, "Pim-Calculation-" + counter.incrementAndGet());
              thread.setDaemon(true);
              thread.setPriority(Thread.MIN_PRIORITY);
              thread.setContextClassLoader(contextClassLoader);
              return thread;
            }
          });

  public static void register(
      com.mojang.brigadier.CommandDispatcher<FabricClientCommandSource> dispatcher) {
    dispatcher.register(
        ClientCommandManager.literal("pim:compute")
            .requires(src -> PimClient.isImagineFunServer())
            .executes(
                context -> {
                  // Start calculation on separate thread
                  startCalculationThread(context.getSource());
                  return 1;
                }));
  }

  private static void startCalculationThread(FabricClientCommandSource source) {
    calculationExecutor.submit(
        () -> {
          try {
            processPinSeriesCalculations(source);
          } catch (Exception e) {
            Minecraft.getInstance()
                .execute(
                    () -> {
                      source.sendFeedback(
                          Component.literal(
                              "§6✨ §e[IMF] §cError during calculation: " + e.getMessage()));
                    });
          }
        });
  }

  private static void processPinSeriesCalculations(FabricClientCommandSource source) {
    // Get all series names from PinRarityHandler
    java.util.Set<String> allSeriesNames = PinRarityHandler.getInstance().getAllSeriesNames();

    if (allSeriesNames.isEmpty()) {
      Minecraft.getInstance()
          .execute(
              () -> {
                source.sendFeedback(
                    Component.literal(
                        "§6✨ §e[IMF] §cNo pin series data available. Please open /pinrarity and /pinbook first."));
              });
      return;
    }

    double totalDraws = 0;
    double totalPrice = 0;

    // Process each series
    for (String seriesName : allSeriesNames) {
      if (!isSeriesValidForCalculation(seriesName)) {
        continue;
      }

      String finalSeriesName = seriesName;

      try {
        // Get pin counts for this series
        Algorithm.PinSeriesCounts counts = PinCalculationUtils.getPinSeriesCounts(seriesName);
        if (counts == null) {
          continue;
        }

        DPResult result =
            PinCalculationUtils.getCachedOrCalculatePlayerSpecificResult(seriesName, counts);

        if (result == null || result.isError()) {
          Minecraft.getInstance()
              .execute(
                  () -> {
                    source.sendFeedback(
                        Component.literal(
                            "§6✨ §e[IMF] §cError calculating "
                                + finalSeriesName
                                + ": "
                                + (result != null ? result.error.get() : "Unknown error")));
                  });
          continue;
        }

        double value = result.value.get();
        double boxes = Math.round(value / 2.0);
        totalDraws += value;

        // Calculate price if series has price data
        String priceStr = null;
        double estimatedPrice = 0;
        PinRarityHandler.PinSeriesEntry seriesEntry =
            PinRarityHandler.getInstance().getSeriesEntry(finalSeriesName);
        if (seriesEntry != null && seriesEntry.color != null) {
          estimatedPrice = boxes * seriesEntry.color.price;
          priceStr = formatPrice(estimatedPrice);
          totalPrice += estimatedPrice;
        }

        final double finalBoxes = boxes;
        final String finalPriceStr = priceStr;

        Minecraft.getInstance()
            .execute(
                () -> {
                  if (result.isSuccess()) {
                    if (finalPriceStr != null) {
                      source.sendFeedback(
                          Component.literal(
                              "§6✨ §e[IMF] §a"
                                  + finalSeriesName
                                  + ": §f"
                                  + String.format("%.0f", finalBoxes)
                                  + " boxes (≈"
                                  + finalPriceStr
                                  + ")"));
                    } else {
                      source.sendFeedback(
                          Component.literal(
                              "§6✨ §e[IMF] §a"
                                  + finalSeriesName
                                  + ": §f"
                                  + String.format("%.0f", finalBoxes)
                                  + " boxes"));
                    }
                  } else if (result.isError()) {
                    source.sendFeedback(
                        Component.literal(
                            "§6✨ §e[IMF] §cError calculating "
                                + finalSeriesName
                                + ": "
                                + result.error.get()));
                  }
                });

        // Small delay between series to prevent overwhelming the system
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }

      } catch (Exception e) {
        Minecraft.getInstance()
            .execute(
                () -> {
                  source.sendFeedback(
                      Component.literal(
                          "§6✨ §e[IMF] §cError processing "
                              + finalSeriesName
                              + ": "
                              + e.getMessage()));
                });
      }
    }

    final double finalTotalDraws = totalDraws;
    final double finalTotalBoxes = finalTotalDraws / 2.0;
    final double finalTotalPrice = totalPrice;
    final String totalBoxesStr = formatPrice(finalTotalBoxes);
    final String totalPriceStr = finalTotalPrice > 0 ? formatPrice(finalTotalPrice) : "N/A";

    Minecraft.getInstance()
        .execute(
            () -> {
              source.sendFeedback(
                  Component.literal(
                      "§6✨ §e[IMF] §6Total: §f"
                          + totalBoxesStr
                          + " boxes"
                          + (finalTotalPrice > 0 ? " (≈" + totalPriceStr + ")" : "")));
            });
  }

  private static boolean isSeriesValidForCalculation(String seriesName) {
    // Check if series has complete information (no blinking condition)
    Map<String, PinDetailHandler.PinDetailEntry> detailMap =
        PinDetailHandler.getInstance().getSeriesDetails(seriesName);

    if (detailMap == null || detailMap.isEmpty()) {
      return false;
    }

    // Get PinBook entry to check totalMints
    PinBookHandler.PinBookEntry bookEntry = PinBookHandler.getInstance().getBookEntry(seriesName);
    if (bookEntry == null) {
      return false;
    }

    // Check if detailMap size matches totalMints (no blinking condition from line 92-94)
    if (detailMap.size() != bookEntry.totalMints) {
      return false;
    }

    // Check if the pin series is complete
    if (bookEntry.totalMints == bookEntry.mintsCollected) {
      return false;
    }

    // Check if all entries have rarity not null
    for (PinDetailHandler.PinDetailEntry entry : detailMap.values()) {
      if (entry.rarity == null) {
        return false;
      }
    }

    // Check if series is REQUIRED (ignore OPTIONAL series)
    PinRarityHandler.PinSeriesEntry seriesEntry =
        PinRarityHandler.getInstance().getSeriesEntry(seriesName);
    if (seriesEntry == null || seriesEntry.availability != PinRarityHandler.Availability.REQUIRED) {
      return false;
    }

    return true;
  }

  private static String formatPrice(double value) {
    if (value >= 1_000_000) {
      return String.format("%.1fM", value / 1_000_000);
    } else if (value >= 1_000) {
      return String.format("%.1fK", value / 1_000);
    } else {
      return String.format("%.0f", value);
    }
  }
}
