package com.chenweikeng.imf.nra.handler;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.ServerState;
import com.chenweikeng.imf.nra.config.ChatImeMode;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Switches the OS keyboard input method to English while the chat screen is open, restoring the
 * player's previous input method when chat closes. In the default {@link
 * ChatImeMode#NON_IF_LANGUAGES} mode, input methods whose language is already used on ImagineFun
 * are left alone; {@link ChatImeMode#ALWAYS} switches regardless.
 *
 * <p>macOS uses the Text Input Source Services API (Carbon/HIToolbox); Windows uses IMM32 on the
 * game window's input context. Both are reached in-process via JNA, which Minecraft ships. Other
 * platforms are a no-op. All native calls run on the render thread, which is the OS main/window
 * thread on both platforms.
 */
public final class ChatImeSwitcher {
  private static final Logger LOGGER = LoggerFactory.getLogger(NotRidingAlertClient.MOD_ID);

  /**
   * Languages used on ImagineFun, as lowercase BCP-47 primary subtags — what macOS reports for an
   * input source. Tagalog appears as both "tl" (Tagalog) and "fil" (Filipino) in the wild.
   */
  private static final Set<String> IF_LANGUAGE_TAGS =
      Set.of("en", "es", "fr", "ja", "nl", "ru", "sv", "tl", "fil");

  /**
   * The same languages as Windows primary language identifiers (winnt.h): LANG_ENGLISH,
   * LANG_SPANISH, LANG_FRENCH, LANG_JAPANESE, LANG_DUTCH, LANG_RUSSIAN, LANG_SWEDISH,
   * LANG_FILIPINO.
   */
  private static final Set<Integer> IF_PRIMARY_LANGIDS =
      Set.of(0x09, 0x0a, 0x0c, 0x11, 0x13, 0x19, 0x1d, 0x64);

  private static boolean active = false;
  private static boolean nativeFailureLogged = false;

  /** macOS: input source selected before we switched to ASCII. Owned ref; CFRelease on restore. */
  private static Pointer macSavedInputSource = null;

  /** macOS: resolved value of the kTISPropertyInputSourceLanguages constant. */
  private static Pointer macLanguagesPropertyKey = null;

  private static boolean winStateSaved = false;
  private static boolean winConversionSaved = false;
  private static boolean winSavedOpen = false;
  private static int winSavedConversion = 0;
  private static int winSavedSentence = 0;

  private ChatImeSwitcher() {}

  public static void register() {
    ScreenEvents.AFTER_INIT.register(
        (client, screen, scaledWidth, scaledHeight) -> {
          if (!(screen instanceof ChatScreen)) {
            return;
          }
          // AFTER_INIT fires again on window resize while the same screen stays open.
          if (active) {
            return;
          }
          ChatImeMode mode = ModConfig.currentSetting.chatImeMode;
          if (mode == ChatImeMode.NEVER || !ServerState.isImagineFunServer()) {
            return;
          }
          active = true;
          handleChatOpen(mode);
          ScreenEvents.remove(screen)
              .register(
                  removed -> {
                    if (active) {
                      active = false;
                      restorePrevious();
                    }
                  });
        });

    // If the game quits with chat open, put the input method back before the process dies —
    // on macOS the selection is system-wide and would otherwise outlive the game.
    ClientLifecycleEvents.CLIENT_STOPPING.register(
        client -> {
          if (active) {
            active = false;
            restorePrevious();
          }
        });
  }

  private static void handleChatOpen(ChatImeMode mode) {
    try {
      String os = System.getProperty("os.name", "").toLowerCase();
      if (os.contains("mac") || os.contains("darwin")) {
        macHandleChatOpen(mode);
      } else if (os.contains("win")) {
        winHandleChatOpen(mode);
      }
    } catch (Throwable t) {
      logNativeFailureOnce(t);
    }
  }

  private static void restorePrevious() {
    try {
      String os = System.getProperty("os.name", "").toLowerCase();
      if (os.contains("mac") || os.contains("darwin")) {
        macRestore();
      } else if (os.contains("win")) {
        winRestore();
      }
    } catch (Throwable t) {
      logNativeFailureOnce(t);
    }
  }

  /** True when the input method's language is one used on ImagineFun, e.g. "ja" or "en-US". */
  private static boolean isIfLanguage(String languageTag) {
    if (languageTag == null) {
      return false;
    }
    String primary = languageTag.toLowerCase(Locale.ROOT);
    int dash = primary.indexOf('-');
    if (dash > 0) {
      primary = primary.substring(0, dash);
    }
    return IF_LANGUAGE_TAGS.contains(primary);
  }

  private static void logNativeFailureOnce(Throwable t) {
    if (!nativeFailureLogged) {
      nativeFailureLogged = true;
      LOGGER.warn("Chat IME switch unavailable on this system", t);
    }
  }

  // --- macOS ---

  private static final int K_CF_STRING_ENCODING_UTF8 = 0x08000100;

  /** Carbon/HIToolbox Text Input Source Services. Selection is per user session, not per app. */
  private interface MacCarbon extends Library {
    MacCarbon INSTANCE = Native.load("Carbon", MacCarbon.class);

    Pointer TISCopyCurrentKeyboardInputSource();

    Pointer TISCopyCurrentASCIICapableKeyboardInputSource();

    int TISSelectInputSource(Pointer inputSource);

    Pointer TISGetInputSourceProperty(Pointer inputSource, Pointer propertyKey);
  }

  private interface MacCoreFoundation extends Library {
    MacCoreFoundation INSTANCE = Native.load("CoreFoundation", MacCoreFoundation.class);

    void CFRelease(Pointer cf);

    long CFArrayGetCount(Pointer array);

    Pointer CFArrayGetValueAtIndex(Pointer array, long index);

    boolean CFStringGetCString(Pointer string, byte[] buffer, long bufferSize, int encoding);
  }

  private static void macHandleChatOpen(ChatImeMode mode) {
    Pointer current = MacCarbon.INSTANCE.TISCopyCurrentKeyboardInputSource();
    if (mode == ChatImeMode.NON_IF_LANGUAGES
        && current != null
        && isIfLanguage(macPrimaryLanguage(current))) {
      MacCoreFoundation.INSTANCE.CFRelease(current);
      return;
    }
    Pointer ascii = MacCarbon.INSTANCE.TISCopyCurrentASCIICapableKeyboardInputSource();
    if (ascii == null) {
      if (current != null) {
        MacCoreFoundation.INSTANCE.CFRelease(current);
      }
      return;
    }
    MacCarbon.INSTANCE.TISSelectInputSource(ascii);
    MacCoreFoundation.INSTANCE.CFRelease(ascii);
    if (macSavedInputSource != null) {
      MacCoreFoundation.INSTANCE.CFRelease(macSavedInputSource);
    }
    macSavedInputSource = current;
  }

  private static void macRestore() {
    if (macSavedInputSource == null) {
      return;
    }
    MacCarbon.INSTANCE.TISSelectInputSource(macSavedInputSource);
    MacCoreFoundation.INSTANCE.CFRelease(macSavedInputSource);
    macSavedInputSource = null;
  }

  /**
   * Primary BCP-47 language tag of an input source ("zh-Hans", "ja", ...), or null if it cannot be
   * determined. The languages property is a CFArray ordered by relevance; per the Get rule it is
   * not owned by us.
   */
  private static String macPrimaryLanguage(Pointer inputSource) {
    if (macLanguagesPropertyKey == null) {
      macLanguagesPropertyKey =
          NativeLibrary.getInstance("Carbon")
              .getGlobalVariableAddress("kTISPropertyInputSourceLanguages")
              .getPointer(0);
    }
    Pointer languages =
        MacCarbon.INSTANCE.TISGetInputSourceProperty(inputSource, macLanguagesPropertyKey);
    if (languages == null || MacCoreFoundation.INSTANCE.CFArrayGetCount(languages) == 0) {
      return null;
    }
    Pointer first = MacCoreFoundation.INSTANCE.CFArrayGetValueAtIndex(languages, 0);
    if (first == null) {
      return null;
    }
    byte[] buffer = new byte[64];
    if (!MacCoreFoundation.INSTANCE.CFStringGetCString(
        first, buffer, buffer.length, K_CF_STRING_ENCODING_UTF8)) {
      return null;
    }
    int length = 0;
    while (length < buffer.length && buffer[length] != 0) {
      length++;
    }
    return new String(buffer, 0, length, StandardCharsets.UTF_8);
  }

  // --- Windows ---

  private static final int IME_CMODE_ALPHANUMERIC = 0;

  private interface WinUser32 extends Library {
    WinUser32 INSTANCE = Native.load("user32", WinUser32.class);

    Pointer GetKeyboardLayout(int threadId);
  }

  private interface WinImm32 extends Library {
    WinImm32 INSTANCE = Native.load("imm32", WinImm32.class);

    Pointer ImmGetContext(Pointer hwnd);

    boolean ImmReleaseContext(Pointer hwnd, Pointer himc);

    boolean ImmGetOpenStatus(Pointer himc);

    boolean ImmSetOpenStatus(Pointer himc, boolean open);

    boolean ImmGetConversionStatus(
        Pointer himc, IntByReference conversion, IntByReference sentence);

    boolean ImmSetConversionStatus(Pointer himc, int conversion, int sentence);
  }

  private static void winHandleChatOpen(ChatImeMode mode) {
    if (mode == ChatImeMode.NON_IF_LANGUAGES
        && IF_PRIMARY_LANGIDS.contains(winPrimaryLanguageId())) {
      return;
    }
    winSwitchToEnglish();
  }

  /**
   * Primary language identifier of the render thread's active keyboard layout / IME. The low word
   * of the HKL is a LANGID; its low 10 bits are the primary language.
   */
  private static int winPrimaryLanguageId() {
    Pointer hkl = WinUser32.INSTANCE.GetKeyboardLayout(0);
    return (int) (Pointer.nativeValue(hkl) & 0x3FF);
  }

  private static Pointer winGameWindow() {
    Minecraft client = Minecraft.getInstance();
    if (client.getWindow() == null) {
      return null;
    }
    long hwnd = GLFWNativeWin32.glfwGetWin32Window(client.getWindow().handle());
    return hwnd == 0 ? null : new Pointer(hwnd);
  }

  private static void winSwitchToEnglish() {
    Pointer hwnd = winGameWindow();
    if (hwnd == null) {
      return;
    }
    Pointer himc = WinImm32.INSTANCE.ImmGetContext(hwnd);
    if (himc == null) {
      // No IME associated with the window (plain English layout) — nothing to switch.
      return;
    }
    try {
      winSavedOpen = WinImm32.INSTANCE.ImmGetOpenStatus(himc);
      IntByReference conversion = new IntByReference();
      IntByReference sentence = new IntByReference();
      winConversionSaved = WinImm32.INSTANCE.ImmGetConversionStatus(himc, conversion, sentence);
      if (winConversionSaved) {
        winSavedConversion = conversion.getValue();
        winSavedSentence = sentence.getValue();
        WinImm32.INSTANCE.ImmSetConversionStatus(himc, IME_CMODE_ALPHANUMERIC, winSavedSentence);
      }
      WinImm32.INSTANCE.ImmSetOpenStatus(himc, false);
      winStateSaved = true;
    } finally {
      WinImm32.INSTANCE.ImmReleaseContext(hwnd, himc);
    }
  }

  private static void winRestore() {
    if (!winStateSaved) {
      return;
    }
    winStateSaved = false;
    Pointer hwnd = winGameWindow();
    if (hwnd == null) {
      return;
    }
    Pointer himc = WinImm32.INSTANCE.ImmGetContext(hwnd);
    if (himc == null) {
      return;
    }
    try {
      if (winConversionSaved) {
        WinImm32.INSTANCE.ImmSetConversionStatus(himc, winSavedConversion, winSavedSentence);
      }
      WinImm32.INSTANCE.ImmSetOpenStatus(himc, winSavedOpen);
    } finally {
      WinImm32.INSTANCE.ImmReleaseContext(hwnd, himc);
    }
  }
}
