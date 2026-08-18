package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.alphatable.AlphaTableRenderer;
import com.chenweikeng.imf.pim.pinpack.PinPackOverlayRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draws the A=1..Z=26 reference overlay on top of container screens after the vanilla render
 * finishes, and consumes mouse clicks that land on one of its doc-link labels so they don't also
 * fire a slot interaction underneath. Container geometry is forwarded to the renderer so it can
 * position the overlay in the gutter to the right of the open menu. Gating lives inside {@link
 * AlphaTableRenderer#renderIfVisible} so the condition can be refined without touching this class.
 */
@Mixin(AbstractContainerScreen.class)
public class ImfAlphaTableContainerScreenMixin {

  @Shadow protected int leftPos;
  @Shadow protected int topPos;
  @Shadow protected int imageWidth;
  @Shadow protected int imageHeight;

  @Inject(at = @At("TAIL"), method = "render")
  public void imf$renderAlphaTable(
      GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
    AlphaTableRenderer.renderIfVisible(
        guiGraphics,
        (Screen) (Object) this,
        this.leftPos,
        this.topPos,
        this.imageWidth,
        this.imageHeight);
  }

  @Inject(at = @At("HEAD"), method = "mouseClicked", require = 1, cancellable = true)
  public void imf$handleAlphaTableLinkClick(
      MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
    if (event.button() == 0) {
      Screen screen = (Screen) (Object) this;
      if (AlphaTableRenderer.handleClick(event.x(), event.y(), screen)
          || PinPackOverlayRenderer.handleClick(event.x(), event.y(), screen)) {
        cir.setReturnValue(true);
      }
    }
  }
}
