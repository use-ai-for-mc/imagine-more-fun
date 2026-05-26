package com.chenweikeng.imf.nra.handler;

import com.chenweikeng.imf.nra.ServerState;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.chenweikeng.imf.nra.ride.LastRideHolder;
import com.chenweikeng.imf.nra.ride.RideName;
import com.chenweikeng.imf.nra.wizard.TutorialManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

public class ScoreboardHandler {
  private static ScoreboardHandler instance;

  public static ScoreboardHandler getInstance() {
    if (instance == null) {
      instance = new ScoreboardHandler();
    }
    return instance;
  }

  private static final Comparator<PlayerScoreEntry> SCORE_DISPLAY_ORDER =
      Comparator.comparing(PlayerScoreEntry::value)
          .reversed()
          .thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER);

  private int tickCounter = 0;
  private static final int TICK_INTERVAL = 10;
  private boolean scoreboardEmpty = false;

  public boolean isScoreboardEmpty() {
    return scoreboardEmpty;
  }

  private long ticksUntilNextReminder = 600;
  private static final int SCOREBOARD_REMINDER_INTERVAL = 12000; // 10 minutes

  private static class TeamInfo {
    private final String teamName;
    private final String prefixStr;

    public TeamInfo(String teamName, String prefixStr) {
      this.teamName = teamName;
      this.prefixStr = prefixStr;
    }

    public String getTeamName() {
      return teamName;
    }

    public String getPrefixStr() {
      return prefixStr;
    }
  }

  public void track(Minecraft client) {
    if (!ServerState.isImagineFunServer()) {
      return;
    }
    if (client.level == null || client.player == null) {
      return;
    }

    if (ticksUntilNextReminder > 200) {
      ticksUntilNextReminder--;
    }

    tickCounter++;
    if (tickCounter < TICK_INTERVAL) {
      return;
    }
    tickCounter = 0;

    Scoreboard scoreboard = client.level.getScoreboard();
    Objective objective = getDisplayObjective(scoreboard, client);
    if (objective == null) {
      scoreboardEmpty = true;
      if (ticksUntilNextReminder > 0 && ticksUntilNextReminder <= 200) {
        ticksUntilNextReminder--;
      }
      sendScoreboardReminder(client);
      return;
    }

    List<TeamInfo> teamInfos = extractTeamInfos(scoreboard, objective, client);
    if (teamInfos.isEmpty()) {
      scoreboardEmpty = true;
      if (ticksUntilNextReminder > 0 && ticksUntilNextReminder <= 200) {
        ticksUntilNextReminder--;
      }
      sendScoreboardReminder(client);
      return;
    }

    scoreboardEmpty = false;
    processRideInfo(teamInfos);
  }

  public void reset() {
    tickCounter = 0;
    scoreboardEmpty = false;
    ticksUntilNextReminder = 600;
  }

  private void sendScoreboardReminder(Minecraft client) {
    if (client.player == null) {
      return;
    }

    if (ticksUntilNextReminder > 0) {
      return;
    }

    if (!TutorialManager.getInstance().isCompletedForCurrentVersion()) {
      return;
    }

    ticksUntilNextReminder = SCOREBOARD_REMINDER_INTERVAL;

    Component message =
        Component.literal("§6✨ §e[IMF] §fThe mod requires ")
            .append(
                Component.literal("§b/sb")
                    .withStyle(
                        s ->
                            s.withUnderlined(true).withClickEvent(new ClickEvent.RunCommand("sb"))))
            .append(
                Component.literal(
                    "§f to receive scoreboard from server. You can hide scoreboard in "))
            .append(
                Component.literal("§b/imf")
                    .withStyle(
                        s ->
                            s.withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand("imf"))))
            .append(Component.literal("§f later if you want."));

    client.player.displayClientMessage(message, false);
  }

  private Objective getDisplayObjective(Scoreboard scoreboard, Minecraft client) {
    Objective objective = null;
    PlayerTeam playerTeam = scoreboard.getPlayersTeam(client.player.getScoreboardName());
    if (playerTeam != null) {
      DisplaySlot displaySlot = DisplaySlot.teamColorToSlot(playerTeam.getColor());
      if (displaySlot != null) {
        objective = scoreboard.getDisplayObjective(displaySlot);
      }
    }
    return objective != null ? objective : scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
  }

  private List<TeamInfo> extractTeamInfos(
      Scoreboard scoreboard, Objective objective, Minecraft client) {
    List<TeamInfo> teamInfos = new ArrayList<>();
    scoreboard.listPlayerScores(objective).stream()
        .filter(score -> !score.isHidden())
        .sorted(SCORE_DISPLAY_ORDER)
        .limit(15L)
        .forEach(
            scoreboardEntry -> {
              PlayerTeam team = scoreboard.getPlayersTeam(scoreboardEntry.owner());
              if (team == null) return;
              String teamName = team.getName();
              var prefixText = team.getPlayerPrefix();
              String prefixStr = prefixText != null ? prefixText.getString() : "null";
              teamInfos.add(new TeamInfo(teamName, prefixStr));
            });

    teamInfos.sort(Comparator.comparing(TeamInfo::getTeamName));

    return teamInfos;
  }

  private void processRideInfo(List<TeamInfo> teamInfos) {
    int currentRideIndex = -1;
    for (int i = 0; i < teamInfos.size(); i++) {
      if (teamInfos.get(i).getPrefixStr().contains("Current Ride")) {
        currentRideIndex = i;
        break;
      }
    }

    String currentRidePrefix = null;
    String timePrefix = null;

    if (currentRideIndex >= 0 && currentRideIndex + 1 < teamInfos.size()) {
      currentRidePrefix = teamInfos.get(currentRideIndex + 1).getPrefixStr();
      if (currentRideIndex + 2 < teamInfos.size()) {
        timePrefix = teamInfos.get(currentRideIndex + 2).getPrefixStr();
      }
    }

    if (currentRidePrefix == null) {
      CurrentRideHolder.setCurrentRide(null);
    } else {
      RideName resolved = RideName.fromTruncatedString(currentRidePrefix);
      CurrentRideHolder.setCurrentRide(resolved);
      LastRideHolder.setLastRide(resolved);

      if (resolved == RideName.DAVY_CROCKETTS_EXPLORER_CANOES) {
        // Canoe progress is published by CanoeHelperClient from the boat's position on the
        // reference track. Don't overwrite it here, but do clear elapsedSeconds — the canoe ride
        // has no fixed duration so an elapsed-based reading is meaningless.
        CurrentRideHolder.setElapsedSeconds(null);
      } else if (timePrefix != null) {
        int elapsed = parseTimeString(timePrefix);
        if (elapsed >= 0) {
          int rideTimeSeconds = resolved.getRideTime();
          if (rideTimeSeconds > 0) {
            int percent = Math.min(100, Math.max(0, (elapsed * 100) / rideTimeSeconds));
            CurrentRideHolder.setCurrentProgressPercent(percent);
            CurrentRideHolder.setElapsedSeconds(elapsed);
          } else {
            CurrentRideHolder.setCurrentProgressPercent(null);
            CurrentRideHolder.setElapsedSeconds(null);
          }
        } else {
          CurrentRideHolder.setCurrentProgressPercent(null);
          CurrentRideHolder.setElapsedSeconds(null);
        }
      } else {
        CurrentRideHolder.setCurrentProgressPercent(null);
        CurrentRideHolder.setElapsedSeconds(null);
      }
    }
  }

  private int parseTimeString(String timeStr) {
    if (timeStr == null || timeStr.isEmpty()) {
      return -1;
    }

    String cleaned = timeStr.trim();
    if (cleaned.startsWith("⏐ ")) {
      cleaned = cleaned.substring(2).trim();
    }
    if (cleaned.startsWith("Time: ")) {
      cleaned = cleaned.substring(6).trim();
    }

    int totalSeconds = 0;
    int currentNumber = 0;

    for (int i = 0; i < cleaned.length(); i++) {
      char c = cleaned.charAt(i);
      if (Character.isDigit(c)) {
        currentNumber = currentNumber * 10 + (c - '0');
      } else if (c == 's' || c == 'S') {
        totalSeconds += currentNumber;
        currentNumber = 0;
      } else if (c == 'm' || c == 'M') {
        totalSeconds += currentNumber * 60;
        currentNumber = 0;
      } else if (c == 'h' || c == 'H') {
        totalSeconds += currentNumber * 3600;
        currentNumber = 0;
      }
    }

    if (currentNumber > 0) {
      totalSeconds += currentNumber;
    }

    return totalSeconds >= 0 ? totalSeconds : -1;
  }
}
