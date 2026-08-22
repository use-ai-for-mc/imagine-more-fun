package com.chenweikeng.imf.nra.handler;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import net.minecraft.client.Minecraft;

public class FireworkViewingHandler {
  private static final int MIN_X = -7;
  private static final int MAX_X = 14;
  private static final int MIN_Z = 485;
  private static final int MAX_Z = 495;
  private static final int MIN_Y_1 = 77;
  private static final int MAX_Y_1 = 78;
  private static final int MIN_Y_2 = 81;
  private static final int MAX_Y_2 = 82;

  private static final long NIGHT = 13000L;

  private boolean isViewingFirework = false;
  private boolean wasViewingFirework = false;

  private static FireworkViewingHandler instance;

  private FireworkViewingHandler() {}

  public static FireworkViewingHandler getInstance() {
    if (instance == null) {
      instance = new FireworkViewingHandler();
    }
    return instance;
  }

  public void track(Minecraft client) {
    wasViewingFirework = isViewingFirework;

    if (client.player == null || client.level == null) {
      isViewingFirework = false;
      return;
    }

    if (!NotRidingAlertClient.isImagineFunServer()) {
      isViewingFirework = false;
      return;
    }

    int x = (int) client.player.getX();
    int y = (int) client.player.getY();
    int z = (int) client.player.getZ();

    boolean inX = x >= MIN_X && x <= MAX_X;
    boolean inZ = z >= MIN_Z && z <= MAX_Z;
    boolean inY = (y >= MIN_Y_1 && y <= MAX_Y_1) || (y >= MIN_Y_2 && y <= MAX_Y_2);

    isViewingFirework = inX && inY && inZ;

    if (isViewingFirework && !wasViewingFirework) {
      handleEnterViewingArea(client);
    }
  }

  private void handleEnterViewingArea(Minecraft client) {
    if (client.level == null) {
      return;
    }

    long time = ClientDayTime.get(client.level) % 24000L;
    if (time < NIGHT) {
      ClientDayTime.set(client.level, NIGHT);
    }
  }

  public boolean isViewingFirework() {
    return isViewingFirework;
  }

  public boolean wasViewingFirework() {
    return wasViewingFirework;
  }

  public void reset() {
    isViewingFirework = false;
    wasViewingFirework = false;
  }
}
