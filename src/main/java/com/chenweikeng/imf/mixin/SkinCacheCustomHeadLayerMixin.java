package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.skincache.cache.TextureCache;
import com.chenweikeng.imf.skincache.prewarm.ProfileTextureExtractor;
import com.chenweikeng.imf.skincache.prewarm.ProfileTextureExtractor.SkinInfo;
import com.chenweikeng.imf.skincache.prewarm.TextureRegistrar;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.SkullBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CustomHeadLayer.class)
public abstract class SkinCacheCustomHeadLayerMixin {
  @Inject(method = "resolveSkullRenderType", at = @At("HEAD"), cancellable = true)
  private void skincache$resolveFromCache(
      LivingEntityRenderState state, SkullBlock.Type type, CallbackInfoReturnable<RenderType> cir) {
    if (type != SkullBlock.Types.PLAYER) return;

    ResolvableProfile profile = state.wornHeadProfile;
    if (profile == null) {
      return;
    }

    // Extract texture URL directly from profile properties (no network call)
    SkinInfo skinInfo = ProfileTextureExtractor.extract(profile);
    if (skinInfo == null) {
      return;
    }

    if (!TextureCache.isCached(skinInfo.textureUrl())) {
      return;
    }

    Identifier textureId = Identifier.withDefaultNamespace(skinInfo.textureIdPath());

    if (!TextureRegistrar.ensureRegistered(textureId, skinInfo.textureUrl())) {
      return;
    }

    cir.setReturnValue(RenderTypes.entityTranslucent(textureId));
  }
}
