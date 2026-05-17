package com.chenweikeng.imf.nra;

import com.chenweikeng.imf.nra.audio.OpenAudioMcService;
import com.chenweikeng.imf.nra.compat.MonkeycraftCompat;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.config.RidePlanNudge;
import com.chenweikeng.imf.nra.config.profile.HistoryManager;
import com.chenweikeng.imf.nra.config.profile.ProfileCommandHandler;
import com.chenweikeng.imf.nra.config.profile.ProfileManager;
import com.chenweikeng.imf.nra.config.profile.ui.ProfileManagementScreen;
import com.chenweikeng.imf.nra.dailyplan.DailyPlanHudRenderer;
import com.chenweikeng.imf.nra.dailyplan.DailyPlanProgressTracker;
import com.chenweikeng.imf.nra.handler.AdvanceNoticeHandler;
import com.chenweikeng.imf.nra.handler.AutograbFailureHandler;
import com.chenweikeng.imf.nra.handler.AutograbRegionRenderer;
import com.chenweikeng.imf.nra.handler.ClosedCaptionHolder;
import com.chenweikeng.imf.nra.handler.ConfigReminderHandler;
import com.chenweikeng.imf.nra.handler.DayTimeHandler;
import com.chenweikeng.imf.nra.handler.FireworkViewingHandler;
import com.chenweikeng.imf.nra.handler.HibernationHandler;
import com.chenweikeng.imf.nra.handler.ReminderHandler;
import com.chenweikeng.imf.nra.handler.ScoreboardHandler;
import com.chenweikeng.imf.nra.report.DailyReport;
import com.chenweikeng.imf.nra.report.DailyReportGenerator;
import com.chenweikeng.imf.nra.report.DailyRideSnapshot;
import com.chenweikeng.imf.nra.report.RideReportChatRenderer;
import com.chenweikeng.imf.nra.report.RideReportNotifier;
import com.chenweikeng.imf.nra.report.ui.RideReportScreen;
import com.chenweikeng.imf.nra.ride.AutograbHolder;
import com.chenweikeng.imf.nra.ride.ClosestRideHolder;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.chenweikeng.imf.nra.ride.RideCountManager;
import com.chenweikeng.imf.nra.ride.RideName;
import com.chenweikeng.imf.nra.session.SessionStatsHudRenderer;
import com.chenweikeng.imf.nra.session.SessionTracker;
import com.chenweikeng.imf.nra.status.StatusBarController;
import com.chenweikeng.imf.nra.strategy.StrategyHudRendererDispatcher;
import com.chenweikeng.imf.nra.tracker.FoodConsumptionTracker;
import com.chenweikeng.imf.nra.tracker.PlayerMovementTracker;
import com.chenweikeng.imf.nra.tracker.RideStateTracker;
import com.chenweikeng.imf.nra.tracker.SuppressionRegionTracker;
import com.chenweikeng.imf.nra.wizard.TutorialManager;
import com.chenweikeng.imf.nra.wizard.WizardScreen;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NotRidingAlertClient implements ClientModInitializer {
  public static final String MOD_ID = "not-riding-alert";
  public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

  private final GameState gameState = GameState.getInstance();
  private final PlayerMovementTracker movementTracker = new PlayerMovementTracker();
  private final RideStateTracker rideStateTracker = new RideStateTracker();
  private final SuppressionRegionTracker suppressionRegionTracker = new SuppressionRegionTracker();
  private final DayTimeHandler dayTimeHandler = new DayTimeHandler();
  private final ConfigReminderHandler configReminderHandler = new ConfigReminderHandler();
  private final AutograbFailureHandler autograbFailureHandler = new AutograbFailureHandler();
  private final FireworkViewingHandler fireworkViewingHandler =
      FireworkViewingHandler.getInstance();
  private final ScoreboardHandler scoreboardHandler = new ScoreboardHandler();
  private final ReminderHandler reminderHandler = ReminderHandler.getInstance();
  private final AlertChecker alertChecker = new AlertChecker();
  private final CursorManager cursorManager = new CursorManager();
  private final AdvanceNoticeHandler advanceNoticeHandler = new AdvanceNoticeHandler();

  private int tickCounter = 0;

  @Override
  public void onInitializeClient() {
    ModConfig.load();
    RidePlanNudge.applyFirstLaunch();
    ProfileManager.load();
    HistoryManager.load();
    DailyRideSnapshot.getInstance();
    LOGGER.info("Not Riding Alert client initialized");
    AutograbRegionRenderer.register();

    ClientPlayConnectionEvents.JOIN.register(
        (handler, sender, client) -> {
          ServerState.onJoin(client);
          client.execute(RidePlanNudge::showMessageIfPending);
          if (ServerState.isImagineFunServer()) {
            MonkeycraftCompat.init();
            SessionTracker.getInstance().onSessionStart();
            if (ModConfig.currentSetting.enableOpenAudioMc) {
              OpenAudioMcService.getInstance().autoConnectOnJoin();
            }
          }
          if (ServerState.isImagineFunServer()
              && TutorialManager.getInstance().shouldStartTutorial()) {
            client.execute(
                () -> {
                  if (client.screen == null) {
                    client.setScreen(new WizardScreen());
                  }
                });
          }
        });

    ClientPlayConnectionEvents.DISCONNECT.register(
        (handler, client) -> {
          SessionTracker.getInstance().onSessionEnd();
          OpenAudioMcService.getInstance().disconnect();
          StatusBarController.getInstance().onDisconnect();
          ServerState.onDisconnect();
          resetAllTrackers();
        });

    ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

    ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, registryAccess) -> {
          registerNraCommand(dispatcher);
          registerOaCommand(dispatcher);
          registerRideReportCommand(dispatcher);
        });

    WorldRenderEvents.AFTER_ENTITIES.register(
        context -> {
          if (!ServerState.isImagineFunServer()) {
            return;
          }
          AutograbRegionRenderer.render(context);
        });

    Identifier beforeChatId =
        Identifier.fromNamespaceAndPath(NotRidingAlertClient.MOD_ID, "before_chat");
    if (beforeChatId != null) {
      HudElementRegistry.attachElementBefore(
          VanillaHudElements.CHAT, beforeChatId, StrategyHudRendererDispatcher::render);
    }

    Identifier dailyPlanHudId =
        Identifier.fromNamespaceAndPath(NotRidingAlertClient.MOD_ID, "daily_plan");
    if (dailyPlanHudId != null) {
      HudElementRegistry.attachElementBefore(
          VanillaHudElements.CHAT, dailyPlanHudId, DailyPlanHudRenderer::render);
    }

    Identifier sessionStatsId =
        Identifier.fromNamespaceAndPath(NotRidingAlertClient.MOD_ID, "session_stats");
    if (sessionStatsId != null) {
      HudElementRegistry.attachElementBefore(
          VanillaHudElements.CHAT, sessionStatsId, SessionStatsHudRenderer::render);
    }
  }

  private void onClientTick(Minecraft client) {
    if (!ServerState.isImagineFunServer()) {
      return;
    }
    if (client.player == null) {
      gameState.setRiding(false);
      return;
    }

    boolean wasPassenger = cursorManager.wasPassenger();
    boolean isPassenger = gameState.isValidPassenger(client.player);
    RideName autograbRide = AutograbHolder.getRideAtLocation(client);

    gameState.updateSittingState(wasPassenger, isPassenger);
    gameState.clearSittingIfNotPassenger(client.player.isPassenger());

    isPassenger = gameState.isValidPassenger(client.player);

    boolean isRiding =
        isPassenger || CurrentRideHolder.getCurrentRide() != null || autograbRide != null;
    gameState.setRiding(isRiding);

    cursorManager.tick(client, isPassenger, isRiding, autograbRide);
    gameState.incrementTickCounter();

    long currentTick = gameState.getAbsoluteTickCounter();
    movementTracker.track(client, currentTick);
    rideStateTracker.trackRideCompletion(currentTick);
    rideStateTracker.trackVehicleState(client, currentTick);
    suppressionRegionTracker.trackLincolnRegionEntryExit(client, rideStateTracker);
    fireworkViewingHandler.track(client);
    dayTimeHandler.resetDayTimeIfNeeded(client);
    boolean autograbFailureActive =
        autograbFailureHandler.track(client, currentTick, movementTracker);
    gameState.setAutograbFailureActive(autograbFailureActive);
    if (autograbFailureActive) {
      cursorManager.handleAutograbFailureRestore();
    } else {
      cursorManager.clearAutograbFailureRestored();
    }
    HibernationHandler.getInstance().track(client, currentTick);
    configReminderHandler.track(client, currentTick);
    scoreboardHandler.track(client);
    ClosestRideHolder.update(client);
    advanceNoticeHandler.tick(client);
    reminderHandler.track(client, currentTick);
    ClosedCaptionHolder.getInstance().tick();

    RideCountManager.getInstance().checkAndSaveIfNeeded();
    DailyPlanProgressTracker.getInstance().tick(client);
    SessionTracker.getInstance().checkAndSaveIfNeeded();
    RideReportNotifier.getInstance().tick();
    FoodConsumptionTracker.getInstance().tick();
    StatusBarController.getInstance().tick(client);

    tickCounter++;
    if (tickCounter >= Timing.ALERT_CHECK_INTERVAL) {
      tickCounter = 0;
      alertChecker.check(
          client,
          autograbFailureActive,
          movementTracker,
          rideStateTracker,
          suppressionRegionTracker);
    }
  }

  private void resetAllTrackers() {
    movementTracker.reset();
    rideStateTracker.reset();
    suppressionRegionTracker.reset();
    autograbFailureHandler.reset();
    configReminderHandler.reset();
    fireworkViewingHandler.reset();
    HibernationHandler.getInstance().reset();
    scoreboardHandler.reset();
    ClosestRideHolder.reset();
    reminderHandler.reset();
    ClosedCaptionHolder.getInstance().clear();
    cursorManager.reset();
    advanceNoticeHandler.reset();
    RideReportNotifier.getInstance().reset();
    gameState.reset();
    tickCounter = 0;
  }

  public static boolean isImagineFunServer() {
    return ServerState.isImagineFunServer();
  }

  private static void registerNraCommand(CommandDispatcher<FabricClientCommandSource> dispatcher) {
    dispatcher.register(
        ClientCommandManager.literal("imf")
            .executes(
                context -> {
                  Minecraft client = Minecraft.getInstance();
                  client.execute(
                      () -> {
                        client.setScreen(new ProfileManagementScreen(client.screen));
                      });
                  return 1;
                })
            .then(
                ClientCommandManager.literal("setup")
                    .executes(
                        context -> {
                          TutorialManager.getInstance().resetTutorial();
                          Minecraft client = Minecraft.getInstance();
                          client.execute(
                              () -> {
                                client.setScreen(new WizardScreen());
                              });
                          return 1;
                        }))
            .then(
                ClientCommandManager.literal("profile")
                    .then(
                        ClientCommandManager.argument(
                                "profileName",
                                com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                            .suggests(
                                (context, builder) -> {
                                  String remaining = builder.getRemaining().toLowerCase();

                                  ProfileManager.getAllProfiles().stream()
                                      .map(profile -> profile.name)
                                      .filter(name -> name.toLowerCase().startsWith(remaining))
                                      .forEach(builder::suggest);
                                  return builder.buildFuture();
                                })
                            .executes(
                                context -> {
                                  String profileName =
                                      com.mojang.brigadier.arguments.StringArgumentType.getString(
                                          context, "profileName");
                                  return ProfileCommandHandler.executeProfileSwitch(profileName);
                                }))));
  }

  private static void registerOaCommand(CommandDispatcher<FabricClientCommandSource> dispatcher) {
    dispatcher.register(
        ClientCommandManager.literal("oa")
            .then(
                ClientCommandManager.literal("connect")
                    .executes(
                        context -> {
                          OpenAudioMcService.getInstance().connectViaCommand();
                          return 1;
                        }))
            .then(
                ClientCommandManager.literal("disconnect")
                    .executes(
                        context -> {
                          OpenAudioMcService.getInstance().disconnectViaCommand();
                          return 1;
                        }))
            .then(
                ClientCommandManager.literal("reconnect")
                    .executes(
                        context -> {
                          OpenAudioMcService.getInstance().reconnectWithFallback();
                          return 1;
                        }))
            .then(
                ClientCommandManager.literal("volume")
                    .executes(
                        context -> {
                          OpenAudioMcService service = OpenAudioMcService.getInstance();
                          int vol = service.getCurrentVolume();
                          Minecraft client = Minecraft.getInstance();
                          if (client != null) {
                            String msg =
                                vol >= 0
                                    ? "Current volume: " + vol + "%"
                                    : "Volume unknown (not connected).";
                            client.execute(
                                () ->
                                    client
                                        .gui
                                        .getChat()
                                        .addMessage(
                                            net.minecraft.network.chat.Component.literal(
                                                "\u00A76\u2728 \u00A7e[IMF] \u00A7f" + msg)));
                          }
                          return 1;
                        })));
  }

  private static void registerRideReportCommand(
      CommandDispatcher<FabricClientCommandSource> dispatcher) {
    dispatcher.register(
        ClientCommandManager.literal("ridereport")
            .executes(
                context -> {
                  Minecraft client = Minecraft.getInstance();
                  client.execute(
                      () -> {
                        if (ServerState.isImagineFunServer()
                            && MonkeycraftCompat.isClientConnected()) {
                          DailyReport report = DailyReportGenerator.generateLive();
                          if (report != null) {
                            RideReportChatRenderer.send(client, report);
                            RideReportNotifier.getInstance().markViewed();
                            return;
                          }
                        }
                        client.setScreen(RideReportScreen.createLive(client.screen));
                      });
                  return 1;
                })
            .then(
                ClientCommandManager.argument(
                        "date", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .executes(
                        context -> {
                          String date =
                              com.mojang.brigadier.arguments.StringArgumentType.getString(
                                  context, "date");
                          Minecraft client = Minecraft.getInstance();
                          client.execute(
                              () -> {
                                if (ServerState.isImagineFunServer()
                                    && MonkeycraftCompat.isClientConnected()) {
                                  DailyReport report = DailyReportGenerator.generate(date);
                                  if (report != null) {
                                    RideReportChatRenderer.send(client, report);
                                    RideReportNotifier.getInstance().markViewed();
                                    return;
                                  }
                                }
                                client.setScreen(new RideReportScreen(client.screen, date));
                              });
                          return 1;
                        })));
  }
}
