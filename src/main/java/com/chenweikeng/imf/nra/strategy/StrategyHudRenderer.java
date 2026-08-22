package com.chenweikeng.imf.nra.strategy;

import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public interface StrategyHudRenderer {
  void update();

  void setError(String error);

  String getError();

  void render(GuiGraphicsExtractor context, DeltaTracker tickCounter);

  List<RideGoal> getTopGoals();
}
