package com.chenweikeng.imf;

import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.canoe.CanoeHelperClient;
import com.chenweikeng.imf.nra.spacemountain.SpaceMountainAnimationRecorder;
import com.chenweikeng.imf.nra.spacemountain.SpaceMountainBlockOverride;
import com.chenweikeng.imf.nra.spacemountain.SpaceMountainDiscoBall;
import com.chenweikeng.imf.nra.spacemountain.SpaceMountainRideAudio;
import com.chenweikeng.imf.nra.spacemountain.SpaceMountainStarRenderer;
import com.chenweikeng.imf.nra.spacemountain.SpaceMountainStlOverlay;
import com.chenweikeng.imf.nra.spacemountain.SpaceMountainTrackRenderer;
import com.chenweikeng.imf.nra.spacemountain.SpaceMountainTunnelRenderer;
import com.chenweikeng.imf.pim.PimClient;
import com.chenweikeng.imf.skincache.SkinCacheMod;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single client entrypoint for the merged ImagineMoreFun mod.
 *
 * <p>This class wires together three formerly-independent mods into one. Each sub-mod's original
 * initializer is still invoked unchanged — the order is NRA → PIM → SkinCache, but none of them has
 * a hard dependency on another, so the order is not load-bearing.
 *
 * <p>Storage migration runs exactly once before any sub-mod initializer touches the filesystem.
 */
public class ImfClient implements ClientModInitializer {

  public static final String MOD_ID = "imaginemorefun";
  public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

  @Override
  public void onInitializeClient() {
    LOGGER.info("ImagineMoreFun starting (NRA + PIM + SkinCache)");

    // Move old on-disk state into config/imaginemorefun/ before any sub-mod reads its files.
    try {
      ImfMigration.runOnce();
    } catch (RuntimeException e) {
      LOGGER.error("Storage migration failed; continuing anyway", e);
    }

    SpaceMountainStarRenderer.register();
    SpaceMountainTrackRenderer.register();
    SpaceMountainBlockOverride.init();
    SpaceMountainAnimationRecorder.register();
    // Freestanding hyperspace streaks are superseded by SpaceMountainTunnelRenderer's
    // cylinder-projected streaks during the warp window. Re-enable if you want both layers.
    // SpaceMountainHyperspaceRenderer.register();
    SpaceMountainTunnelRenderer.register();
    SpaceMountainStlOverlay.register();
    SpaceMountainDiscoBall.register();
    SpaceMountainRideAudio.register();

    new NotRidingAlertClient().onInitializeClient();
    new PimClient().onInitializeClient();
    new SkinCacheMod().onInitializeClient();
    CanoeHelperClient.init();
  }
}
