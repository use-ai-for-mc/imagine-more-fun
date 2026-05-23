package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.audio.OpenAudioMcService;
import com.chenweikeng.imf.nra.config.ClosedCaptionMode;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.handler.ClosedCaptionHolder;
import com.chenweikeng.imf.nra.handler.HibernationHandler;
import com.chenweikeng.imf.nra.handler.ReminderHandler;
import com.chenweikeng.imf.nra.ride.LastRideHolder;
import com.chenweikeng.imf.nra.ride.RideCountManager;
import com.chenweikeng.imf.nra.ride.RideName;
import com.chenweikeng.imf.nra.session.SessionTracker;
import com.chenweikeng.imf.pim.PimClient;
import com.chenweikeng.imf.pim.hoarder.PinHoarderHelper;
import java.awt.Color;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatListener.class)
public class NraChatListenerMixin {
  private static final String RIDE_OVERVIEW_MARKER = "<<-----------| Ride Overview |----------->>";
  private static final String ATTRACTION_OVERVIEW_MARKER =
      "<<-----------| Attraction Overview |----------->>";
  private static final String CC_MARKER = "[CC]";

  @Inject(at = @At("HEAD"), method = "handleSystemMessage", cancellable = true)
  private void onGameMessage(Component message, boolean overlay, CallbackInfo ci) {
    if (!NotRidingAlertClient.isImagineFunServer()) {
      return;
    }
    if (message == null) return;

    String msg = message.getString();

    if (msg.startsWith(CC_MARKER)) {
      ClosedCaptionMode mode = ModConfig.currentSetting.closedCaptionMode;
      if (mode == ClosedCaptionMode.HIDDEN) {
        ci.cancel();
        return;
      }
      if (mode != ClosedCaptionMode.NONE) {
        handleClosedCaption(message);
        ci.cancel();
        return;
      }
    }
    if (ModConfig.currentSetting.hideLovePotionMessages && msg.contains(": §d§o")) {
      ci.cancel();
      return;
    }

    if (msg.equals("You are now connected with the audio client!")) {
      ReminderHandler.getInstance().setAudioConnected(true);
      if (ModConfig.currentSetting.enableOpenAudioMc) {
        OpenAudioMcService.getInstance().onServerConfirmedConnection();
      }
      return;
    }
    if (msg.equals("You are already connected to the web client")) {
      if (ModConfig.currentSetting.enableOpenAudioMc) {
        // May be benign (we're genuinely connected) or a desync (server holds the old
        // session while our webview lost it). The service decides and recovers.
        OpenAudioMcService.getInstance().onServerAlreadyConnected();
      } else {
        ReminderHandler.getInstance().setAudioConnected(true);
      }
      return;
    }
    if (msg.equals("Your audio session has been ended")) {
      ReminderHandler.getInstance().setAudioConnected(false);
      if (ModConfig.currentSetting.enableOpenAudioMc) {
        OpenAudioMcService.getInstance().onServerEndedSession();
      }
      return;
    }
    if (msg.equals("    + Audio Boost (x1.25)")) {
      ReminderHandler.getInstance().setAudioConnected(true);
      if (ModConfig.currentSetting.enableOpenAudioMc) {
        OpenAudioMcService.getInstance().onServerConfirmedConnection();
      }
      return;
    }

    // Check for OpenAudioMC session URLs in ClickEvents.
    // connect() is run async because WebViewBridge.start() blocks up to 15s waiting for the
    // native helper process — running it on the render thread would visibly freeze the game.
    if (ModConfig.currentSetting.enableOpenAudioMc
        || OpenAudioMcService.getInstance().isPendingCommandConnect()) {
      String sessionUrl = OpenAudioMcService.extractSessionUrl(message);
      if (sessionUrl != null) {
        CompletableFuture.runAsync(() -> OpenAudioMcService.getInstance().connect(sessionUrl));
      }
    }

    // Detect Pin Hoarder trade completion (coin pickup message)
    // Format: " + (8x Common) Kingdom Coin" or similar
    if (PimClient.isImagineFunServer() && msg.startsWith(" + (") && msg.contains("Kingdom Coin")) {
      SessionTracker.getInstance().onPinHoarderTrade();
      PinHoarderHelper.onTradeCompleted();
    }

    if (!msg.contains(RIDE_OVERVIEW_MARKER) && !msg.contains(ATTRACTION_OVERVIEW_MARKER)) return;

    RideName lastRide = LastRideHolder.getLastRide();
    if (lastRide == null) {
      return;
    }
    if (lastRide == RideName.UNKNOWN) {
      return;
    }

    RideCountManager countManager = RideCountManager.getInstance();
    int current = countManager.getRideCount(lastRide);
    countManager.updateRideCount(lastRide, current + 1);

    SessionTracker.getInstance().onRideCompleted(lastRide);

    HibernationHandler.getInstance().cancelPendingCancellation();
  }

  private void handleClosedCaption(Component message) {
    List<Component> parts = message.toFlatList(Style.EMPTY);

    int separatorIndex = -1;
    for (int i = 0; i < parts.size(); i++) {
      Component part = parts.get(i);
      if (part.getString().equals(" ") && part.getStyle().isEmpty()) {
        separatorIndex = i;
        break;
      }
    }

    if (separatorIndex < 0 || separatorIndex >= parts.size() - 1) {
      return;
    }

    int startIndex = separatorIndex + 1;

    Component firstPart = parts.get(startIndex);
    TextColor firstColor = firstPart.getStyle().getColor();
    if (firstPart.getString().equals(": ")
        && firstColor != null
        && firstColor.getValue() == 0xFFFFFF) {
      startIndex++;
    }

    if (startIndex >= parts.size()) {
      return;
    }

    StringBuilder contentBuilder = new StringBuilder();
    for (int i = startIndex; i < parts.size(); i++) {
      contentBuilder.append(parts.get(i).getString());
    }
    String contentText = contentBuilder.toString();

    String announcerName = null;
    int colonIndex = contentText.indexOf(": ");
    if (colonIndex > 0) {
      announcerName = contentText.substring(0, colonIndex).trim();
    }

    MutableComponent overlayComponent = Component.empty();

    boolean shouldRecolor =
        ModConfig.currentSetting.closedCaptionMode == ClosedCaptionMode.RECOLORED
            && announcerName != null
            && !announcerName.equals("immediately seeing");

    TextColor textColor = null;
    if (shouldRecolor) {
      Color announcerColor = ClosedCaptionHolder.getInstance().colorFromName(announcerName);
      textColor = TextColor.fromRgb(announcerColor.getRGB() & 0xFFFFFF);
    }

    for (int i = startIndex; i < parts.size(); i++) {
      Component part = parts.get(i).copy();
      if (textColor != null) {
        part = part.copy().withStyle(part.getStyle().withColor(textColor));
      }
      overlayComponent.append(part);
    }

    ClosedCaptionHolder.getInstance().setCaption(overlayComponent);
  }
}
