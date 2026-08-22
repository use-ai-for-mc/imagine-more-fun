package com.chenweikeng.imf;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Unified chat message formatting for ImagineMoreFun mod. All player-facing messages should use
 * these helpers to ensure consistent styling across PIM, NRA, and other subsystems.
 *
 * <p>Standard prefixes:
 *
 * <ul>
 *   <li>{@link #PREFIX} - Normal messages: "§6✨ §e[IMF] §f"
 *   <li>{@link #PREFIX_SUCCESS} - Success messages: "§6✨ §e[IMF] §a✓ "
 *   <li>{@link #PREFIX_ERROR} - Error messages: "§6✨ §e[IMF] §c⚠ "
 *   <li>{@link #PREFIX_WARN} - Warning messages: "§c⚠ §e[IMF] §f"
 *   <li>{@link #PREFIX_NAV} - Navigation/warp messages: "§b➜ §e[IMF] §f"
 * </ul>
 */
public final class ImfChat {

  /** Normal message prefix: sparkle + yellow [IMF] + white text */
  public static final String PREFIX = "§6✨ §e[IMF] §f";

  /** Success message prefix: sparkle + yellow [IMF] + green checkmark */
  public static final String PREFIX_SUCCESS = "§6✨ §e[IMF] §a✓ ";

  /** Error message prefix: sparkle + yellow [IMF] + red warning */
  public static final String PREFIX_ERROR = "§6✨ §e[IMF] §c⚠ ";

  /** Warning message prefix: red warning + yellow [IMF] + white text */
  public static final String PREFIX_WARN = "§c⚠ §e[IMF] §f";

  /** Navigation/warp message prefix: cyan arrow + yellow [IMF] + white text */
  public static final String PREFIX_NAV = "§b➜ §e[IMF] §f";

  // Color codes for inline use
  public static final String WHITE = "§f";
  public static final String YELLOW = "§e";
  public static final String GREEN = "§a";
  public static final String RED = "§c";
  public static final String AQUA = "§b";
  public static final String GOLD = "§6";

  private ImfChat() {}

  /** Sends a normal message to the player. */
  public static void send(String message) {
    sendRaw(PREFIX + message);
  }

  /** Sends a success message to the player. */
  public static void sendSuccess(String message) {
    sendRaw(PREFIX_SUCCESS + message);
  }

  /** Sends an error message to the player. */
  public static void sendError(String message) {
    sendRaw(PREFIX_ERROR + message);
  }

  /** Sends a warning message to the player. */
  public static void sendWarn(String message) {
    sendRaw(PREFIX_WARN + message);
  }

  /** Sends a navigation/warp message to the player. */
  public static void sendNav(String message) {
    sendRaw(PREFIX_NAV + message);
  }

  /** Sends a raw message (no prefix) to the player. */
  public static void sendRaw(String message) {
    var player = Minecraft.getInstance().player;
    if (player != null) {
      player.sendSystemMessage(Component.literal(message));
    }
  }

  /** Sends a message to the action bar. */
  public static void sendActionBar(String message) {
    var player = Minecraft.getInstance().player;
    if (player != null) {
      player.sendOverlayMessage(Component.literal(message));
    }
  }

  /** Creates a Component with the normal prefix. */
  public static Component component(String message) {
    return Component.literal(PREFIX + message);
  }

  /** Creates a Component with the success prefix. */
  public static Component componentSuccess(String message) {
    return Component.literal(PREFIX_SUCCESS + message);
  }

  /** Creates a Component with the error prefix. */
  public static Component componentError(String message) {
    return Component.literal(PREFIX_ERROR + message);
  }

  /** Creates a Component with the warning prefix. */
  public static Component componentWarn(String message) {
    return Component.literal(PREFIX_WARN + message);
  }

  /** Creates a Component with the navigation prefix. */
  public static Component componentNav(String message) {
    return Component.literal(PREFIX_NAV + message);
  }
}
