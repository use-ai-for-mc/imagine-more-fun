package com.chenweikeng.imf.nra.spacemountain;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/** Render types shared by the Space Mountain overlay geometry. */
public final class ImfRenderPipelines {
  private ImfRenderPipelines() {}

  /** Opaque, lightmap-aware entity geometry using the supplied texture. */
  public static RenderType opaqueScreen(Identifier texture) {
    return RenderTypes.entitySolid(texture);
  }
}
