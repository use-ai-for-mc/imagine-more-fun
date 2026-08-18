package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.nra.spacemountain.SpaceMountainEntityHider;
import com.chenweikeng.imf.nra.spacemountain.SpaceMountainOverride;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hides ImagineFun show-prop armor stands (TIE Fighters, X-Wings — armor stands wearing
 * custom-modelled diamond swords in the helmet slot) while the player is riding Space or Hyperspace
 * Mountain.
 *
 * <p>Forcing {@link EntityRenderer#shouldRender} to return {@code false} skips the entity entirely
 * — no geometry, no lighting, no name tag. {@code shouldRender} is declared only on {@code
 * EntityRenderer} (neither {@code LivingEntityRenderer} nor {@code ArmorStandRenderer} overrides
 * it), so the armor stand's render dispatch hits this method directly.
 */
@Mixin(EntityRenderer.class)
public class NraEntityRendererHideMixin {
  @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
  private void imf$hideShowPropsForRide(
      Entity entity,
      Frustum frustum,
      double camX,
      double camY,
      double camZ,
      CallbackInfoReturnable<Boolean> cir) {
    if (SpaceMountainOverride.isActive()
        && entity instanceof ArmorStand stand
        && SpaceMountainEntityHider.shouldHide(stand)) {
      cir.setReturnValue(false);
    }
  }
}
