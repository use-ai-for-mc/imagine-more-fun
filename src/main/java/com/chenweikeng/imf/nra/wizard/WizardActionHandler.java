package com.chenweikeng.imf.nra.wizard;

import com.chenweikeng.imf.nra.config.AudioBoostReminderMode;
import com.chenweikeng.imf.nra.config.ClosedCaptionMode;
import com.chenweikeng.imf.nra.config.CursorReleaseTiming;
import com.chenweikeng.imf.nra.config.FullbrightMode;
import com.chenweikeng.imf.nra.config.MaxGoal;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.config.SortingRules;
import com.chenweikeng.imf.nra.config.StrategyHudRendererVersion;
import com.chenweikeng.imf.nra.config.TrackerDisplayMode;
import com.chenweikeng.imf.nra.config.WindowMinimizeTiming;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

public class WizardActionHandler {
  public static Identifier currentlyPlaying = null;

  public static void handle(String action, Minecraft client) {
    if (action == null || action.isEmpty()) {
      return;
    }

    if (action.startsWith("page:")) {
      handlePageAction(action.substring(5), client);
    } else if (action.startsWith("config:")) {
      handleConfigAction(action.substring(7), client);
    } else if (action.startsWith("ride:")) {
      handleRideAction(action.substring(5), client);
    } else if (action.startsWith("sound_preview:")) {
      handleSoundPreviewAction(action.substring(14), client);
    } else if (action.startsWith("command:")) {
      handleCommandAction(action.substring(8), client);
    } else if (action.equals("finish")) {
      handleFinish(client);
    }
  }

  private static void handlePageAction(String pageStr, Minecraft client) {
    try {
      int pageIndex = Integer.parseInt(pageStr.trim());
      TutorialManager.getInstance().goToPage(pageIndex);
      if (client.gui.screen() instanceof WizardScreen wizardScreen) {
        wizardScreen.goToPage(pageIndex);
      }
    } catch (NumberFormatException e) {
      // Invalid page number
    }
  }

  private static void handleConfigAction(String configAction, Minecraft client) {
    // Handle advanceNotice specially: advanceNotice:rideName:value
    if (configAction.startsWith("advanceNotice:")) {
      handleAdvanceNoticeAction(configAction.substring("advanceNotice:".length()), client);
      return;
    }

    String[] parts = configAction.split(":", 2);
    if (parts.length != 2) {
      return;
    }

    String key = parts[0];
    String value = parts[1];
    boolean boolValue = Boolean.parseBoolean(value);

    boolean needsRefresh = false;

    switch (key) {
      case "enabled" -> {
        ModConfig.currentSetting.enabled = boolValue;
        ModConfig.save();
        needsRefresh = true;
      }
      case "hideChat" -> {
        ModConfig.currentSetting.hideChat = boolValue;
        ModConfig.save();
      }
      case "hideScoreboard" -> {
        ModConfig.currentSetting.hideScoreboard = boolValue;
        ModConfig.save();
      }
      case "hideHealth" -> {
        ModConfig.currentSetting.hideHealth = boolValue;
        ModConfig.save();
      }
      case "hideHotbar" -> {
        ModConfig.currentSetting.hideHotbar = boolValue;
        ModConfig.save();
      }
      case "soundId" -> {
        ModConfig.currentSetting.soundId = value;
        ModConfig.save();
        needsRefresh = true;
      }
      case "silent" -> {
        ModConfig.currentSetting.silent = boolValue;
        ModConfig.save();
        needsRefresh = true;
      }
      case "audioBoostReminderMode" -> {
        try {
          ModConfig.currentSetting.audioBoostReminderMode = AudioBoostReminderMode.valueOf(value);
          ModConfig.save();
          needsRefresh = true;
        } catch (IllegalArgumentException e) {
          // Invalid mode
        }
      }
      case "blindWhenRiding" -> {
        ModConfig.currentSetting.blindWhenRiding = boolValue;
        ModConfig.save();
        needsRefresh = true;
      }
      case "fullbrightWhenRiding" -> {
        ModConfig.currentSetting.fullbrightMode =
            combineFullbright(boolValue, isFullbrightWhenNotRiding());
        ModConfig.save();
        needsRefresh = true;
      }
      case "fullbrightWhenNotRiding" -> {
        ModConfig.currentSetting.fullbrightMode =
            combineFullbright(isFullbrightWhenRiding(), boolValue);
        ModConfig.save();
        needsRefresh = true;
      }
      case "fullbrightMode" -> {
        try {
          ModConfig.currentSetting.fullbrightMode = FullbrightMode.valueOf(value);
          ModConfig.save();
          needsRefresh = true;
        } catch (IllegalArgumentException e) {
          // Invalid mode
        }
      }
      case "closedCaptionMode" -> {
        try {
          ModConfig.currentSetting.closedCaptionMode = ClosedCaptionMode.valueOf(value);
          ModConfig.save();
          needsRefresh = true;
        } catch (IllegalArgumentException e) {
          // Invalid mode
        }
      }
      case "cursorReleaseTiming" -> {
        try {
          ModConfig.currentSetting.cursorReleaseTiming = CursorReleaseTiming.valueOf(value);
          ModConfig.save();
          needsRefresh = true;
        } catch (IllegalArgumentException e) {
          // Invalid mode
        }
      }
      case "showAutograbRegions" -> {
        ModConfig.currentSetting.showAutograbRegions = boolValue;
        ModConfig.save();
        needsRefresh = true;
      }
      case "showSessionStats" -> {
        ModConfig.currentSetting.showSessionStats = boolValue;
        ModConfig.save();
        needsRefresh = true;
      }
      case "enableOpenAudioMc" -> {
        ModConfig.currentSetting.enableOpenAudioMc = boolValue;
        ModConfig.save();
        needsRefresh = true;
      }
      case "trackerDisplayMode" -> {
        try {
          ModConfig.currentSetting.trackerDisplayMode = TrackerDisplayMode.valueOf(value);
          ModConfig.save();
          needsRefresh = true;
        } catch (IllegalArgumentException e) {
          // Invalid mode
        }
      }
      case "minimizeWindow" -> {
        try {
          ModConfig.currentSetting.minimizeWindow = WindowMinimizeTiming.valueOf(value);
          ModConfig.save();
          needsRefresh = true;
        } catch (IllegalArgumentException e) {
          // Invalid mode
        }
      }
      case "onlyAutograbbing" -> {
        ModConfig.currentSetting.onlyAutograbbing = boolValue;
        ModConfig.save();
        needsRefresh = true;
      }
      case "hideNameTag" -> {
        ModConfig.currentSetting.hideNameTag = boolValue;
        ModConfig.save();
        needsRefresh = true;
      }
      case "hideExperienceLevel" -> {
        ModConfig.currentSetting.hideExperienceLevel = boolValue;
        ModConfig.save();
        needsRefresh = true;
      }
      case "hideLovePotionMessages" -> {
        ModConfig.currentSetting.hideLovePotionMessages = boolValue;
        ModConfig.save();
        needsRefresh = true;
      }
      case "rideDisplayCount" -> {
        try {
          int intValue = Integer.parseInt(value);
          int clampedValue = Math.max(1, Math.min(60, intValue));
          ModConfig.currentSetting.rideDisplayCount = clampedValue;
          ModConfig.save();
          needsRefresh = true;
        } catch (NumberFormatException e) {
          // Invalid number
        }
      }
      case "maxGoal" -> {
        try {
          ModConfig.currentSetting.maxGoal = MaxGoal.valueOf(value);
          ModConfig.save();
          needsRefresh = true;
        } catch (IllegalArgumentException e) {
          // Invalid mode
        }
      }
      case "minRideTimeMinutes" -> {
        try {
          int intValue = Integer.parseInt(value);
          int clampedValue = Math.min(16, intValue);
          ModConfig.currentSetting.minRideTimeMinutes = clampedValue;
          ModConfig.save();
          needsRefresh = true;
        } catch (NumberFormatException e) {
          // Invalid number
        }
      }
      case "strategyHudRendererVersion" -> {
        try {
          ModConfig.currentSetting.strategyHudRendererVersion =
              StrategyHudRendererVersion.valueOf(value);
          ModConfig.save();
          needsRefresh = true;
        } catch (IllegalArgumentException e) {
          // Invalid version
        }
      }
      case "strategyHubUseTotalHours" -> {
        ModConfig.currentSetting.strategyHubUseTotalHours = boolValue;
        ModConfig.save();
        needsRefresh = true;
      }
      case "sortingRules" -> {
        try {
          ModConfig.currentSetting.sortingRules = SortingRules.valueOf(value);
          ModConfig.save();
          needsRefresh = true;
        } catch (IllegalArgumentException e) {
          // Invalid mode
        }
      }
      default -> {
        // Unknown config key
      }
    }

    if (needsRefresh) {
      refreshCurrentPage(client);
    }
  }

  private static void handleAdvanceNoticeAction(String action, Minecraft client) {
    String[] parts = action.split(":", 2);
    if (parts.length != 2) {
      return;
    }
    String rideName = parts[0];
    try {
      int seconds = Integer.parseInt(parts[1]);
      int clamped = Math.max(0, Math.min(30, seconds));
      if (clamped > 0) {
        ModConfig.currentSetting.advanceNoticeSeconds.put(rideName, clamped);
      } else {
        ModConfig.currentSetting.advanceNoticeSeconds.remove(rideName);
      }
      ModConfig.save();
      refreshCurrentPage(client);
    } catch (NumberFormatException e) {
      // Invalid number
    }
  }

  private static void handleRideAction(String rideName, Minecraft client) {
    Set<String> hiddenRides = ModConfig.currentSetting.hiddenRides;
    if (hiddenRides.contains(rideName)) {
      hiddenRides.remove(rideName);
    } else {
      hiddenRides.add(rideName);
    }
    ModConfig.save();
    refreshCurrentPage(client);
  }

  private static void handleSoundPreviewAction(String soundId, Minecraft client) {
    if (client.player == null || client.level == null) {
      return;
    }

    Identifier soundIdentifier = Identifier.parse(soundId);

    if (client.getSoundManager().getSoundEvent(soundIdentifier) == null) {
      soundIdentifier =
          Identifier.fromNamespaceAndPath("minecraft", "entity.experience_orb.pickup");
    }

    currentlyPlaying = soundIdentifier;

    client.level.playSound(
        client.player,
        client.player.getX(),
        client.player.getY(),
        client.player.getZ(),
        SoundEvent.createVariableRangeEvent(soundIdentifier),
        SoundSource.MASTER,
        1.0f,
        1.0f);

    currentlyPlaying = null;
  }

  public static void refreshCurrentPage(Minecraft client) {
    if (client.gui.screen() instanceof WizardScreen ws) {
      ws.goToPage(ws.getCurrentPageIndex());
    }
  }

  private static void handleCommandAction(String command, Minecraft client) {
    if (client.player != null) {
      client.player.connection.sendCommand(command);
    }
  }

  private static void handleFinish(Minecraft client) {
    TutorialManager.getInstance().finishTutorial();
    client.setScreenAndShow(null);
  }

  private static boolean isFullbrightWhenRiding() {
    return ModConfig.currentSetting.fullbrightMode == FullbrightMode.ONLY_WHEN_RIDING
        || ModConfig.currentSetting.fullbrightMode == FullbrightMode.ALWAYS;
  }

  private static boolean isFullbrightWhenNotRiding() {
    return ModConfig.currentSetting.fullbrightMode == FullbrightMode.ONLY_WHEN_NOT_RIDING
        || ModConfig.currentSetting.fullbrightMode == FullbrightMode.ALWAYS;
  }

  private static FullbrightMode combineFullbright(boolean whenRiding, boolean whenNotRiding) {
    if (whenRiding && whenNotRiding) {
      return FullbrightMode.ALWAYS;
    } else if (whenRiding) {
      return FullbrightMode.ONLY_WHEN_RIDING;
    } else if (whenNotRiding) {
      return FullbrightMode.ONLY_WHEN_NOT_RIDING;
    } else {
      return FullbrightMode.NONE;
    }
  }
}
