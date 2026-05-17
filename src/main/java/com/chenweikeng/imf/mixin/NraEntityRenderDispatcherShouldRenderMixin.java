package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.spacemountain.SpaceMountainOverride;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hides specific show-effect entities from the rider's view while on Space/Hyperspace Mountain.
 *
 * <p>Currently suppresses any {@link ItemFrame} whose displayed item is {@code
 * minecraft:nether_star} — those frames are used as on-track lighting effects in the launch tunnel
 * area, but read as floating yellow stars to a passenger. Hiding them on the ride keeps the
 * dim-track aesthetic consistent.
 *
 * <p>Returning {@code false} from {@code EntityRenderDispatcher.shouldRender} skips both render-
 * state extraction and the actual draw call — zero per-frame cost when the gate is inactive.
 */
@Mixin(EntityRenderDispatcher.class)
public class NraEntityRenderDispatcherShouldRenderMixin {
  @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
  private void imf$hideShowFxOnRide(
      Entity entity,
      Frustum culler,
      double camX,
      double camY,
      double camZ,
      CallbackInfoReturnable<Boolean> cir) {
    if (!SpaceMountainOverride.isActive()) return;
    if (entity instanceof ItemFrame frame && frame.getItem().is(Items.NETHER_STAR)) {
      cir.setReturnValue(false);
    }
  }
}
