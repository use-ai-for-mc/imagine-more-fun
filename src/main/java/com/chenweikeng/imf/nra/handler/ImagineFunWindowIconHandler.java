package com.chenweikeng.imf.nra.handler;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.mojang.blaze3d.platform.IconSet;
import com.mojang.blaze3d.platform.MacosUtil;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Applies the ImagineFun logo to the macOS Dock or Windows taskbar while connected. */
public final class ImagineFunWindowIconHandler {
  private static final String ICON_RESOURCE = "/assets/imaginemorefun/imaginefun-window-icon.png";

  private enum Os {
    MAC,
    WINDOWS,
    OTHER
  }

  private static final Os OS = detectOs();
  private static boolean applied;

  private ImagineFunWindowIconHandler() {}

  public static void onJoin(Minecraft client) {
    ServerData server = client.getCurrentServer();
    if (server != null && isImagineFunAddress(server.ip)) {
      apply(client);
    }
  }

  public static void onDisconnect(Minecraft client) {
    if (!applied) {
      return;
    }

    try {
      IconSet iconSet =
          SharedConstants.getCurrentVersion().stable() ? IconSet.RELEASE : IconSet.SNAPSHOT;
      client.getWindow().setIcon(client.getVanillaPackResources(), iconSet);
      applied = false;
      NotRidingAlertClient.LOGGER.info("Restored the Minecraft application icon");
    } catch (Exception error) {
      NotRidingAlertClient.LOGGER.warn("Failed to restore the Minecraft application icon", error);
    }
  }

  static boolean isImagineFunAddress(String address) {
    if (address == null) {
      return false;
    }

    String host = address.strip().toLowerCase(Locale.ROOT);
    int firstColon = host.indexOf(':');
    if (firstColon >= 0 && firstColon == host.lastIndexOf(':')) {
      host = host.substring(0, firstColon);
    }
    if (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    return host.equals("imaginefun.net") || host.endsWith(".imaginefun.net");
  }

  private static void apply(Minecraft client) {
    if (applied) {
      return;
    }

    try {
      switch (OS) {
        case MAC -> MacosUtil.loadIcon(ImagineFunWindowIconHandler::openIcon);
        case WINDOWS -> applyWindows(client);
        case OTHER -> {
          return;
        }
      }
      applied = true;
      NotRidingAlertClient.LOGGER.info("Applied the ImagineFun application icon");
    } catch (Exception error) {
      NotRidingAlertClient.LOGGER.warn("Failed to apply the ImagineFun application icon", error);
    }
  }

  private static void applyWindows(Minecraft client) throws IOException {
    try (InputStream input = openIcon();
        NativeImage image = NativeImage.read(input);
        MemoryStack stack = MemoryStack.stackPush()) {
      ByteBuffer pixels = MemoryUtil.memAlloc(image.getWidth() * image.getHeight() * 4);
      try {
        pixels.asIntBuffer().put(image.getPixelsABGR());
        GLFWImage.Buffer icons = GLFWImage.malloc(1, stack);
        icons.position(0);
        icons.width(image.getWidth());
        icons.height(image.getHeight());
        icons.pixels(pixels);
        GLFW.glfwSetWindowIcon(client.getWindow().handle(), icons.position(0));
      } finally {
        MemoryUtil.memFree(pixels);
      }
    }
  }

  private static InputStream openIcon() throws IOException {
    InputStream input = ImagineFunWindowIconHandler.class.getResourceAsStream(ICON_RESOURCE);
    if (input == null) {
      throw new IOException("Missing classpath resource: " + ICON_RESOURCE);
    }
    return input;
  }

  private static Os detectOs() {
    String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (osName.contains("mac") || osName.contains("darwin")) {
      return Os.MAC;
    }
    if (osName.contains("win")) {
      return Os.WINDOWS;
    }
    return Os.OTHER;
  }
}
