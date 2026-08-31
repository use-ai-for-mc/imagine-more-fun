package com.chenweikeng.imf.skincache;

import com.chenweikeng.imf.skincache.cache.TextureCache;
import com.chenweikeng.imf.skincache.prewarm.ProfileCache;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SkinCacheMod implements ClientModInitializer {

  public static final String MOD_ID = "skincache";
  public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

  @Override
  public void onInitializeClient() {
    TextureCache.init();
    ProfileCache.init();
  }
}
