package com.chenweikeng.imf.nra.compat;

import com.chenweikeng.imf.nra.GameState;
import com.chenweikeng.imf.nra.audio.OpenAudioMcService;
import com.chenweikeng.monkeycraft_api.v1.CommandExecutionResult;
import com.chenweikeng.monkeycraft_api.v1.MonkeycraftApi;

final class MonkeycraftCompatImpl {
  static void init() {
    MonkeycraftApi.CONNECTION.register(
        remoteAddr -> GameState.getInstance().setMonkeyAttached(true));
    MonkeycraftApi.DISCONNECTION.register(() -> GameState.getInstance().setMonkeyAttached(false));
    MonkeycraftApi.INFO_PACKET.register(
        (title, payload) -> {
          if ("openaudiomc".equals(title)
              && payload.has("active")
              && payload.get("active").isJsonPrimitive()
              && payload.getAsJsonPrimitive("active").isBoolean()) {
            OpenAudioMcService.getInstance()
                .setRemoteAudioActive(payload.get("active").getAsBoolean());
          }
        });
    MonkeycraftApi.COMMAND_EXECUTION.register(
        command -> {
          if (command == null) {
            return CommandExecutionResult.PASS;
          }

          String trimmed = command.trim();
          if (trimmed.startsWith("/send ") || trimmed.startsWith("/pay ")) {
            return CommandExecutionResult.DENY;
          }
          if (trimmed.startsWith("/w ")
              || trimmed.startsWith("/warp ")
              || trimmed.equals("/deposit")) {
            return CommandExecutionResult.ALLOW;
          }

          return CommandExecutionResult.PASS;
        });
    MonkeycraftApi.INCOMING_CHAT.register(ChatFormatter::onIncomingChat);
    MonkeycraftApi.OUTGOING_CHAT.register(ChatFormatter::onOutgoingChat);
  }

  static boolean isClientConnected() {
    return MonkeycraftApi.isClientConnected();
  }

  static boolean isServerStarted() {
    return MonkeycraftApi.isServerStarted();
  }

  static boolean isHibernating() {
    return MonkeycraftApi.isHibernating();
  }

  static void setTimedNotification(
      Long fireAtEpochMs, String title, String body, boolean sound, String countDownText) {
    MonkeycraftApi.setTimedNotification(fireAtEpochMs, title, body, sound, countDownText);
  }

  static void cancelTimedNotification() {
    MonkeycraftApi.cancelTimedNotification();
  }

  static void sendImmediateNotification(String title, String body, boolean sound) {
    MonkeycraftApi.sendImmediateNotification(title, body, sound);
  }

  static void startHibernation(String message) {
    MonkeycraftApi.startHibernation(message);
  }

  static void setHibernationMessage(String message) {
    MonkeycraftApi.setHibernationMessage(message);
  }

  static void endHibernation() {
    MonkeycraftApi.endHibernation();
  }
}
