package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.skincache.cache.TextureCache;
import com.chenweikeng.imf.skincache.prewarm.ProfileTextureExtractor;
import com.chenweikeng.imf.skincache.prewarm.ProfileTextureExtractor.SkinInfo;
import com.chenweikeng.imf.skincache.prewarm.TextureRegistrar;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SkullBlockRenderer.class)
public abstract class SkinCacheSkullBlockRendererMixin {
  @Inject(
      method =
          "resolveSkullRenderType(Lnet/minecraft/world/level/block/SkullBlock$Type;Lnet/minecraft/world/level/block/entity/SkullBlockEntity;)Lnet/minecraft/client/renderer/rendertype/RenderType;",
      at = @At("HEAD"),
      cancellable = true)
  private void skincache$resolveFromCache(
      SkullBlock.Type type, SkullBlockEntity entity, CallbackInfoReturnable<RenderType> cir) {
    if (type != SkullBlock.Types.PLAYER) return;

    ResolvableProfile ownerProfile = entity.getOwnerProfile();
    if (ownerProfile == null) {
      return;
    }

    // Extract texture URL directly from profile properties (no network call)
    SkinInfo skinInfo = ProfileTextureExtractor.extract(ownerProfile);
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
