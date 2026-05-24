package com.chenweikeng.imf.mixin;

import com.chenweikeng.imf.ImfClient;
import com.chenweikeng.imf.nra.GameState;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.config.ClosedCaptionMode;
import com.chenweikeng.imf.nra.config.HideCrosshairMode;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.handler.ClosedCaptionHolder;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class NraGuiMixin {
  @Shadow @Final private Minecraft minecraft;

  // Font id path = the font/ JSON filename (nunito_sans.json) under assets/imaginemorefun/.
  private static final FontDescription NUNITO_SANS_FONT =
      new FontDescription.Resource(
          Identifier.fromNamespaceAndPath(ImfClient.MOD_ID, "nunito_sans"));

  @Inject(
      at = @At("HEAD"),
      method =
          "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/scores/Objective;)V",
      cancellable = true)
  private void onRenderScoreboardSidebar(
      GuiGraphics context, Objective objective, CallbackInfo ci) {
    if (!NotRidingAlertClient.isImagineFunServer()) {
      return;
    }
    if (ModConfig.currentSetting.hideScoreboard) {
      ci.cancel();
    }
  }

  @Inject(
      at = @At("HEAD"),
      method =
          "renderChat(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
      cancellable = true)
  private void onRenderChat(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
    if (!NotRidingAlertClient.isImagineFunServer()) {
      return;
    }
    if (ModConfig.currentSetting.hideChat) {
      ci.cancel();
    }
  }

  @Inject(at = @At("HEAD"), method = "renderPlayerHealth", cancellable = true)
  private void onRenderPlayerHealth(GuiGraphics guiGraphics, CallbackInfo ci) {
    if (!NotRidingAlertClient.isImagineFunServer()) {
      return;
    }
    if (GameState.getInstance().isMonkeyAttached() || ModConfig.currentSetting.hideHealth) {
      ci.cancel();
    }
  }

  @Inject(at = @At("HEAD"), method = "renderVehicleHealth", cancellable = true)
  private void onRenderVehicleHealth(GuiGraphics guiGraphics, CallbackInfo ci) {
    if (!NotRidingAlertClient.isImagineFunServer()) {
      return;
    }
    if (GameState.getInstance().isMonkeyAttached() || ModConfig.currentSetting.hideHealth) {
      ci.cancel();
    }
  }

  @Inject(at = @At("HEAD"), method = "renderItemHotbar", cancellable = true)
  private void onRenderItemHotbar(
      GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
    if (!NotRidingAlertClient.isImagineFunServer()) {
      return;
    }
    if (ModConfig.currentSetting.hideHotbar) {
      ci.cancel();
    }
  }

  @Redirect(
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;renderExperienceLevel(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;I)V"),
      method = "renderHotbarAndDecorations")
  private void redirectRenderExperienceLevel(
      GuiGraphics guiGraphics, net.minecraft.client.gui.Font font, int level) {
    if (!NotRidingAlertClient.isImagineFunServer()
        || !ModConfig.currentSetting.hideExperienceLevel) {
      net.minecraft.client.gui.contextualbar.ContextualBarRenderer.renderExperienceLevel(
          guiGraphics, font, level);
    }
  }

  @Inject(at = @At("HEAD"), method = "renderCrosshair", cancellable = true)
  private void onRenderCrosshair(
      GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
    if (!NotRidingAlertClient.isImagineFunServer()) {
      return;
    }
    HideCrosshairMode hideMode = ModConfig.currentSetting.hideCrosshairMode;
    if (hideMode == HideCrosshairMode.ALWAYS) {
      ci.cancel();
      return;
    }
    if (hideMode == HideCrosshairMode.ONLY_WHEN_RIDING
        && minecraft.player != null
        && minecraft.player.isPassenger()) {
      ci.cancel();
      return;
    }
    ClosedCaptionMode ccMode = ModConfig.currentSetting.closedCaptionMode;
    if (ccMode == ClosedCaptionMode.NONE || ccMode == ClosedCaptionMode.HIDDEN) {
      return;
    }
    if (!ClosedCaptionHolder.getInstance().shouldDisplay()) {
      return;
    }
    Component caption = ClosedCaptionHolder.getInstance().getCaption();
    if (caption == null) {
      return;
    }
    ci.cancel();
  }

  @Inject(at = @At("HEAD"), method = "renderTitle", cancellable = true)
  private void onRenderTitle(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
    if (!NotRidingAlertClient.isImagineFunServer()) {
      return;
    }
    ClosedCaptionMode ccMode = ModConfig.currentSetting.closedCaptionMode;
    if (ccMode == ClosedCaptionMode.NONE || ccMode == ClosedCaptionMode.HIDDEN) {
      return;
    }

    if (!ClosedCaptionHolder.getInstance().shouldDisplay()) {
      return;
    }

    Component caption = ClosedCaptionHolder.getInstance().getCaption();
    if (caption == null) {
      return;
    }

    ci.cancel();

    Font font = minecraft.font;
    int guiWidth = guiGraphics.guiWidth();
    int guiHeight = guiGraphics.guiHeight();

    float scale = 2.0f;
    int maxWidth = (int) (guiWidth * 0.8f / scale);
    int textColor = ARGB.color(255, 255, 255, 255);
    int shadowColor = ARGB.color(128, 255, 255, 255);
    Component fontedCaption = applyNunitoToWhitelist(caption);
    Component shadowCaption = applyNunitoToWhitelist(scaleCaptionColors(caption));
    List<net.minecraft.util.FormattedCharSequence> linesShadow =
        font.split(shadowCaption, maxWidth);
    List<net.minecraft.util.FormattedCharSequence> lines = font.split(fontedCaption, maxWidth);

    if (lines.isEmpty()) {
      return;
    }

    int lineHeight = font.lineHeight;
    int lineSpacing = 2;
    int totalHeight = lines.size() * (lineHeight + lineSpacing) - lineSpacing;

    guiGraphics.nextStratum();

    int[][] directions = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}};

    for (int[] dir : directions) {
      guiGraphics.pose().pushMatrix();
      guiGraphics
          .pose()
          .translate(
              (float) guiWidth / 2 + (float) dir[0] * 1.5f,
              (float) guiHeight / 2 + (float) dir[1] * 1.5f);
      guiGraphics.pose().pushMatrix();
      guiGraphics.pose().scale(scale, scale);

      int startY = -totalHeight / 2;
      for (int i = 0; i < lines.size(); i++) {
        net.minecraft.util.FormattedCharSequence lineShadow = linesShadow.get(i);
        int lineWidth = font.width(lineShadow);
        int x = -lineWidth / 2;
        guiGraphics.drawString(font, lineShadow, x, startY, shadowColor, false);
        startY += lineHeight + lineSpacing;
      }

      guiGraphics.pose().popMatrix();
      guiGraphics.pose().popMatrix();
    }

    guiGraphics.pose().pushMatrix();
    guiGraphics.pose().translate((float) guiWidth / 2, (float) guiHeight / 2);
    guiGraphics.pose().pushMatrix();
    guiGraphics.pose().scale(scale, scale);

    int startY = -totalHeight / 2;
    for (int i = 0; i < lines.size(); i++) {
      net.minecraft.util.FormattedCharSequence line = lines.get(i);
      int lineWidth = font.width(line);
      int x = -lineWidth / 2;
      guiGraphics.drawString(font, line, x, startY, textColor, false);
      startY += lineHeight + lineSpacing;
    }

    guiGraphics.pose().popMatrix();
    guiGraphics.pose().popMatrix();
  }

  // Apply Nunito Sans only to whitelisted (normal ASCII) codepoints; leave everything else in the
  // inherited default font. Server resource-pack symbols (e.g. note icons) live in Private-Use-Area
  // codepoints that Nunito has no glyph for, so forcing our font on them would show missing-glyph
  // boxes — keeping the default font lets the server pack draw them.
  private static Component applyNunitoToWhitelist(Component component) {
    MutableComponent result = Component.empty();
    for (Component part : component.toFlatList(Style.EMPTY)) {
      Style style = part.getStyle();
      String text = part.getString();
      int i = 0;
      int n = text.length();
      while (i < n) {
        int cp = text.codePointAt(i);
        boolean nunito = isNunitoGlyph(cp);
        int start = i;
        i += Character.charCount(cp);
        while (i < n) {
          int next = text.codePointAt(i);
          if (isNunitoGlyph(next) != nunito) {
            break;
          }
          i += Character.charCount(next);
        }
        Style runStyle = nunito ? style.withFont(NUNITO_SANS_FONT) : style;
        result.append(Component.literal(text.substring(start, i)).setStyle(runStyle));
      }
    }
    return result;
  }

  // Printable ASCII (0x20-0x7E) + printable Latin-1 Supplement (0xA0-0xFF) — the ranges Nunito Sans
  // covers. Everything else (e.g. server resource-pack symbols in Private-Use-Area codepoints)
  // falls
  // back to the default font.
  private static boolean isNunitoGlyph(int codePoint) {
    return (codePoint >= 0x20 && codePoint <= 0x7E) || (codePoint >= 0xA0 && codePoint <= 0xFF);
  }

  private static Component scaleCaptionColors(Component component) {
    List<Component> flatList = component.toFlatList(Style.EMPTY);
    MutableComponent result = Component.empty();
    for (Component part : flatList) {
      result.append(
          part.copy()
              .withStyle(
                  s -> {
                    Integer color = s.getColor() != null ? s.getColor().getValue() : 0xFFFFFF;
                    int scaledColor = ARGB.scaleRGB(ARGB.opaque(color), 0.25f);
                    return s.withColor(scaledColor);
                  }));
    }
    return result;
  }
}
