package com.chenweikeng.imf.pim.tracker;

import com.chenweikeng.imf.pim.PimState;
import com.chenweikeng.imf.pim.trader.PinTrader;
import com.chenweikeng.imf.pim.trader.PinTraderLocation;
import com.chenweikeng.imf.pim.trader.PinTraderRegistry;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;

public class BossBarTracker {
  // Pin Trader's SHA-256 UUID (generated with Python):
  //   import hashlib, uuid
  //   name = 'Pin Trader'
  //   namespace = uuid.UUID('6ba7b810-9dad-11d1-80b4-00c04fd430c8')
  //   data = namespace.bytes + name.encode('utf-8')
  //   sha256 = hashlib.sha256(data).digest()
  //   uuid_bytes = sha256[:16]
  //   uuid_bytes = bytearray(uuid_bytes)
  //   uuid_bytes[6] = (uuid_bytes[6] & 0x0F) | 0x50
  //   uuid_bytes[8] = (uuid_bytes[8] & 0x3F) | 0x80
  //   print(uuid.UUID(bytes=uuid_bytes))
  public static final UUID PIN_TRADER_BOSS_ID =
      UUID.fromString("aefee8e1-9557-5602-811d-f9ae0f4b4303");

  private static final double CLOSE_DISTANCE = 3.0;
  private static final long NEXT_TRADER_DISPLAY_TIME_MS = 3000;
  private static final long SWITCH_COOLDOWN_MS = 1000;
  private static final long TELEPORT_COOLDOWN_MS = 2000;
  private static BossBarTracker instance;

  private final Minecraft mc;
  private PinTraderLocation currentLocation;
  private PinTrader targetTrader;
  private boolean showingNextTrader;
  private Component displayTitle;
  private boolean hasStartedWarping;
  private long lastSwitchTime;
  private long nextTraderStartTime;
  private long teleportCooldownUntil = 0;
  private Set<PinTrader> visitedTraders;

  private BossBarTracker() {
    this.mc = Minecraft.getInstance();
    this.currentLocation = null;
    this.targetTrader = null;
    this.showingNextTrader = false;
    this.displayTitle = Component.empty();
    this.hasStartedWarping = false;
    this.lastSwitchTime = 0;
    this.nextTraderStartTime = 0;
    this.visitedTraders = new HashSet<>();
  }

  public static BossBarTracker getInstance() {
    if (instance == null) {
      instance = new BossBarTracker();
    }
    return instance;
  }

  public void enable() {
    hasStartedWarping = true;
    teleportCooldownUntil = System.currentTimeMillis() + TELEPORT_COOLDOWN_MS;
    updateForWarpPoint();
  }

  public void disable() {
    hasStartedWarping = false;
    currentLocation = null;
    targetTrader = null;
    showingNextTrader = false;
    displayTitle = Component.empty();
    lastSwitchTime = 0;
    nextTraderStartTime = 0;
    teleportCooldownUntil = 0;
    visitedTraders.clear();
  }

  public void update() {
    if (!hasStartedWarping || mc.player == null || mc.level == null) {
      displayTitle = Component.empty();
      return;
    }

    if (System.currentTimeMillis() < teleportCooldownUntil) {
      displayTitle = Component.empty();
      return;
    }

    String currentWarpPoint = PimState.getActiveWarpPoint();

    if (currentWarpPoint == null) {
      displayTitle = Component.empty();
      return;
    }

    PinTraderLocation location =
        PinTraderRegistry.getInstance().getLocationByWarpPoint(currentWarpPoint);

    if (location == null) {
      displayTitle = Component.empty();
      return;
    }

    if (currentLocation != location) {
      currentLocation = location;
      showingNextTrader = false;
      targetTrader = location.getFirstTrader();
      lastSwitchTime = 0;
      nextTraderStartTime = 0;
      visitedTraders.clear();
    }

    if (allTradersVisited(location)) {
      PinTraderLocation nextLocation = PinTraderRegistry.getInstance().getNextLocation(location);
      if (nextLocation != null) {
        displayTitle = createNextLocationPrompt(nextLocation);
      } else {
        PinTraderLocation firstLocation = PinTraderRegistry.getInstance().getFirstLocation();
        if (firstLocation != null) {
          displayTitle = createReturnToFirstLocationPrompt(firstLocation);
        } else {
          displayTitle = Component.empty();
        }
      }
      return;
    }

    long currentTime = System.currentTimeMillis();

    if (showingNextTrader && (currentTime - nextTraderStartTime) > NEXT_TRADER_DISPLAY_TIME_MS) {
      showingNextTrader = false;
    }

    if (targetTrader != null) {
      double distance = mc.player.distanceToSqr(targetTrader.getNpcVec3());
      long timeSinceLastSwitch = currentTime - lastSwitchTime;

      if (distance < CLOSE_DISTANCE * CLOSE_DISTANCE
          && !showingNextTrader
          && timeSinceLastSwitch >= SWITCH_COOLDOWN_MS) {
        switchToNextTrader(location, mc.player);
      }

      updateDisplayTitle(mc.player, targetTrader);
    }
  }

  private void switchToNextTrader(PinTraderLocation location, LocalPlayer player) {
    java.util.List<PinTrader> traders = location.getTraders();
    int currentIndex = -1;

    for (int i = 0; i < traders.size(); i++) {
      if (traders.get(i) == targetTrader) {
        currentIndex = i;
        break;
      }
    }

    visitedTraders.add(targetTrader);

    int nextIndex = currentIndex + 1;
    if (nextIndex >= traders.size()) {
      nextIndex = 0;
    }

    targetTrader = traders.get(nextIndex);
    showingNextTrader = true;
    // Play bell sound when player gets close to pin trader
    mc.getSoundManager()
        .play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.0f, 1.0f));
    nextTraderStartTime = System.currentTimeMillis();
    lastSwitchTime = nextTraderStartTime;
  }

  private void updateDisplayTitle(LocalPlayer player, PinTrader trader) {
    Vec3 playerPos = player.position();
    Vec3 traderPos = trader.getNpcVec3();

    double distance = playerPos.distanceTo(traderPos);
    String direction = getDirection(player, traderPos);

    Style prefixStyle = Style.EMPTY.withColor(ChatFormatting.DARK_PURPLE).withBold(true);
    Style distanceStyle = Style.EMPTY.withColor(ChatFormatting.GRAY).withBold(false);

    if (showingNextTrader) {
      displayTitle =
          Component.literal("Next Pin Trader")
              .setStyle(prefixStyle)
              .append(
                  Component.literal(String.format(" [%.1fm] %s", distance, direction))
                      .setStyle(distanceStyle));
    } else {
      displayTitle =
          Component.literal("Pin Trader")
              .setStyle(prefixStyle)
              .append(
                  Component.literal(String.format(" [%.1fm] %s", distance, direction))
                      .setStyle(distanceStyle));
    }
  }

  private String getDirection(LocalPlayer player, Vec3 targetPos) {
    Vec3 playerPos = player.position();

    double dx = targetPos.x - playerPos.x;
    double dz = targetPos.z - playerPos.z;

    double absoluteAngle = Math.atan2(-dx, dz);

    float playerYaw = player.getYRot();
    double playerYawRad = Math.toRadians(playerYaw);

    double relativeAngle = absoluteAngle - playerYawRad + Math.PI;

    while (relativeAngle < -Math.PI) {
      relativeAngle += Math.PI * 2;
    }
    while (relativeAngle > Math.PI) {
      relativeAngle -= Math.PI * 2;
    }

    String[] directions = {"⬆", "↗", "➡", "↘", "⬇", "↙", "⬅", "↖"};
    double anglePerDirection = Math.PI * 2 / 8;
    int index = (int) Math.round((relativeAngle + Math.PI) / anglePerDirection) % 8;

    return directions[index];
  }

  private boolean allTradersVisited(PinTraderLocation location) {
    for (PinTrader trader : location.getTraders()) {
      if (!visitedTraders.contains(trader)) {
        return false;
      }
    }
    return true;
  }

  private Component createNextLocationPrompt(PinTraderLocation nextLocation) {
    Style glitchedStyle = Style.EMPTY.withColor(ChatFormatting.GRAY).withObfuscated(true);
    Style textStyle =
        Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true).withObfuscated(false);

    return Component.literal("\u2588\u2588\u2588\u2588\u2588")
        .setStyle(glitchedStyle)
        .append(Component.literal(" Go to " + nextLocation.getName() + " ").setStyle(textStyle))
        .append(Component.literal("\u2588\u2588\u2588\u2588\u2588").setStyle(glitchedStyle));
  }

  private Component createReturnToFirstLocationPrompt(PinTraderLocation firstLocation) {
    Style glitchedStyle = Style.EMPTY.withColor(ChatFormatting.GRAY).withObfuscated(true);
    Style textStyle =
        Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true).withObfuscated(false);

    return Component.literal("\u2588\u2588\u2588\u2588\u2588")
        .setStyle(glitchedStyle)
        .append(
            Component.literal(" Return to " + firstLocation.getName() + " ").setStyle(textStyle))
        .append(Component.literal("\u2588\u2588\u2588\u2588\u2588").setStyle(glitchedStyle));
  }

  public void updateForWarpPoint() {
    String currentWarpPoint = PimState.getCurrentWarpPoint();
    if (currentWarpPoint == null) {
      return;
    }

    PinTraderLocation location =
        PinTraderRegistry.getInstance().getLocationByWarpPoint(currentWarpPoint);
    if (location != null) {
      currentLocation = location;
      showingNextTrader = false;
      targetTrader = location.getFirstTrader();
    }
  }

  public boolean isEnabled() {
    return hasStartedWarping;
  }

  public Component getDisplayTitle() {
    return displayTitle;
  }
}
