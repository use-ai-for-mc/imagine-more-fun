package com.chenweikeng.imf.nra.canoe;

import com.chenweikeng.imf.ImfClient;
import com.chenweikeng.imf.nra.ServerState;
import com.chenweikeng.imf.nra.ride.CurrentRideHolder;
import com.chenweikeng.imf.nra.ride.RideName;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * Tracks the player's canoe session on "Davy Crockett's Explorer Canoes" and exposes two signals
 * other features need:
 *
 * <ul>
 *   <li>{@link #hasCanoeStarted()} — used by {@code CursorManager} to defer window minimisation
 *       until the player has actually started the canoe (first speed-bar update).
 *   <li>Progress percent — projects the boat's position onto the baked reference track and
 *       publishes it to {@link CurrentRideHolder} for the ride-plan HUD, strategy hub, and macOS
 *       status bar.
 * </ul>
 *
 * <p>All accesses are routed through {@link #get()}; {@link com.chenweikeng.imf.mixin
 * .CanoeGuiSetOverlayMessageMixin} calls {@link #onActionBar} from its hook.
 */
public final class CanoeHelperClient {

  /** The display name of the paddle item. Used as the activation gate. */
  public static final String PADDLE_NAME = "Canoe Paddle";

  /** How long the paddle must be missing before the canoe session is considered over. */
  private static final long IDLE_CLOSE_MS = 5_000;

  /** If the boat strays farther than this from the reference path, drop the progress reading. */
  private static final float OFF_TRACK_THRESHOLD_BLOCKS = 15f;

  private static final CanoeHelperClient INSTANCE = new CanoeHelperClient();

  /**
   * True once any canoe-bar action-bar has been received in the current paddle session. Cleared
   * whenever the session ends (paddle gone for {@link #IDLE_CLOSE_MS}, or the player leaves the
   * server). This is the "the canoe actually started moving" signal — used by {@code CursorManager}
   * to defer window minimisation until the player has had a chance to make the start click, and as
   * the gate for publishing progress.
   */
  private volatile boolean canoeStarted = false;

  /** Wall-clock millis at which we last saw the player holding the paddle. */
  private long lastPaddleSeenMs = 0;

  private CanoeHelperClient() {}

  public static CanoeHelperClient get() {
    return INSTANCE;
  }

  /** Wire client-tick callback. Call once from {@link com.chenweikeng.imf.ImfClient}. */
  public static void init() {
    ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::onClientTick);
    ImfClient.LOGGER.info("[Canoe] helper initialised");
  }

  /**
   * Has the canoe started moving in the current session? Becomes true on the first canoe-bar
   * action-bar update and stays true until the paddle session ends (paddle gone for {@link
   * #IDLE_CLOSE_MS}). Used by {@code CursorManager} to gate window minimisation.
   */
  public boolean hasCanoeStarted() {
    return canoeStarted;
  }

  /** Called from {@link com.chenweikeng.imf.mixin.CanoeGuiSetOverlayMessageMixin}. */
  public void onActionBar(Component component) {
    if (CanoeBarParser.parse(component).isCanoeBar()) {
      canoeStarted = true;
    }
  }

  /* -------- per-tick driver -------- */

  private void onClientTick(Minecraft client) {
    if (!ServerState.isImagineFunServer()) {
      canoeStarted = false;
      return;
    }
    LocalPlayer player = client.player;
    if (player == null) {
      canoeStarted = false;
      return;
    }

    long now = System.currentTimeMillis();
    if (isHoldingPaddle(player)) {
      lastPaddleSeenMs = now;
    } else if (canoeStarted && now - lastPaddleSeenMs > IDLE_CLOSE_MS) {
      canoeStarted = false;
    }

    publishProgress(player);
  }

  /**
   * If the player is currently on the canoe ride and the canoe has started, project their position
   * onto the reference track and publish the progress percent to {@link CurrentRideHolder}. Other
   * surfaces (ride plan HUD, strategy hub, macOS status bar) read from there.
   *
   * <p>If the canoe hasn't started yet, or the projection is too far from the reference path
   * (player is off-track somehow), we leave the progress at null rather than publishing noise.
   */
  private void publishProgress(LocalPlayer player) {
    if (CurrentRideHolder.getCurrentRide() != RideName.DAVY_CROCKETTS_EXPLORER_CANOES) return;
    if (!canoeStarted) return;
    Entity v = player.getVehicle();
    double x = v != null ? v.getX() : player.getX();
    double z = v != null ? v.getZ() : player.getZ();
    CanoeTrackModel track = CanoeTrackModel.get();
    if (!track.isLoaded()) return;
    if (track.distanceToTrack(x, z) > OFF_TRACK_THRESHOLD_BLOCKS) return;
    int p = track.progressPercent(x, z);
    if (p < 0) return;
    CurrentRideHolder.setCurrentProgressPercent(p);
  }

  /* -------- helpers -------- */

  private static boolean isHoldingPaddle(LocalPlayer player) {
    return isPaddle(player.getMainHandItem()) || isPaddle(player.getOffhandItem());
  }

  private static boolean isPaddle(ItemStack stack) {
    if (stack == null || stack.isEmpty()) return false;
    String name = stack.getHoverName().getString();
    return PADDLE_NAME.equals(name);
  }
}
