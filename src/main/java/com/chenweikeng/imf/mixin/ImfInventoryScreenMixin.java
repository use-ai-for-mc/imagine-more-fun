package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.pim.pinpack.PinPackOverlayRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for InventoryScreen to render pin pack overlay. Shows counts of opened/unopened pin packs.
 * Click handling is done via ImfAlphaTableContainerScreenMixin on the parent class.
 */
@Mixin(InventoryScreen.class)
public abstract class ImfInventoryScreenMixin extends AbstractContainerScreen<InventoryMenu> {

  private ImfInventoryScreenMixin() {
    super(null, null, null);
  }

  @Inject(at = @At("TAIL"), method = "extractRenderState")
  public void imf$renderPinPackOverlay(
      GuiGraphicsExtractor guiGraphics,
      int mouseX,
      int mouseY,
      float partialTick,
      CallbackInfo ci) {
    PinPackOverlayRenderer.renderIfVisible(
        guiGraphics,
        (Screen) (Object) this,
        this.leftPos,
        this.topPos,
        this.imageWidth,
        this.imageHeight);
  }
}
