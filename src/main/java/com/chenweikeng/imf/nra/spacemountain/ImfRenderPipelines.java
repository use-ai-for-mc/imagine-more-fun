package com.chenweikeng.imf.nra.spacemountain;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * Custom render pipelines for Space Mountain overlay geometry.
 *
 * <p>{@link #ENTITY_THROUGH_WALLS} is a non-emissive equivalent of vanilla {@code
 * ENTITY_TRANSLUCENT} with {@link DepthTestFunction#NO_DEPTH_TEST} so the track stays visible
 * behind dome walls during testing in singleplayer. Because it's non-emissive, the lightmap is
 * respected — a low light coord passed via {@code setLight} renders the track dim, a higher coord
 * brighter. This lets the rider see the track as a faint metal structure rather than a glowing
 * line.
 *
 * <p>Vanilla snippets like {@code MATRICES_FOG_LIGHT_DIR_SNIPPET} are package-private so we declare
 * each uniform/sampler explicitly. Values track {@code RenderPipelines.ENTITY_TRANSLUCENT} which
 * itself extends the entity snippet chain (matrices + fog + lighting + Sampler0/2).
 */
public final class ImfRenderPipelines {

  /**
   * Non-emissive translucent entity pipeline with depth test disabled. Uses {@code core/entity}
   * shaders, respects the lightmap (Sampler2). Pair with a {@code setLight} call carrying a low
   * coord value to render dim.
   */
  /**
   * Opaque entity-style pipeline: no blending, depth test + depth write enabled, back-face culling
   * on. Use for solid surfaces where the per-pixel alpha of the texture must be ignored (paired
   * with a pure-white texture, the result is just {@code vertex_color × lightmap}).
   */
  public static final RenderPipeline OPAQUE_SCREEN =
      RenderPipeline.builder()
          .withLocation(Identifier.fromNamespaceAndPath("imaginemorefun", "pipeline/opaque_screen"))
          .withVertexShader("core/entity")
          .withFragmentShader("core/entity")
          .withSampler("Sampler0")
          .withSampler("Sampler1")
          .withSampler("Sampler2")
          .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
          .withUniform("Projection", UniformType.UNIFORM_BUFFER)
          .withUniform("Fog", UniformType.UNIFORM_BUFFER)
          .withUniform("Lighting", UniformType.UNIFORM_BUFFER)
          .withCull(true)
          .withDepthWrite(true)
          .withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
          .build();

  public static final RenderPipeline ENTITY_THROUGH_WALLS =
      RenderPipeline.builder()
          .withLocation(
              Identifier.fromNamespaceAndPath("imaginemorefun", "pipeline/entity_through_walls"))
          .withVertexShader("core/entity")
          .withFragmentShader("core/entity")
          .withShaderDefine("ALPHA_CUTOUT", 0.1F)
          .withShaderDefine("PER_FACE_LIGHTING")
          .withSampler("Sampler0") // entity texture
          .withSampler("Sampler1") // overlay
          .withSampler("Sampler2") // lightmap
          .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
          .withUniform("Projection", UniformType.UNIFORM_BUFFER)
          .withUniform("Fog", UniformType.UNIFORM_BUFFER)
          .withUniform("Lighting", UniformType.UNIFORM_BUFFER)
          .withBlend(BlendFunction.TRANSLUCENT)
          .withCull(false)
          .withDepthWrite(false)
          .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
          .withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
          .build();

  private ImfRenderPipelines() {}

  /**
   * Translucent triangles with no texture, no cull, depth test on, depth write off. For overlay
   * geometry rendered via {@link DefaultVertexFormat#POSITION_COLOR} — STL surfaces, debug shapes.
   */
  public static final RenderPipeline TRANSLUCENT_TRIANGLES =
      RenderPipeline.builder()
          .withLocation(
              Identifier.fromNamespaceAndPath("imaginemorefun", "pipeline/translucent_triangles"))
          .withVertexShader("core/position_color")
          .withFragmentShader("core/position_color")
          .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
          .withUniform("Projection", UniformType.UNIFORM_BUFFER)
          .withUniform("Fog", UniformType.UNIFORM_BUFFER)
          .withBlend(BlendFunction.TRANSLUCENT)
          .withCull(false)
          .withDepthWrite(false)
          .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES)
          .build();

  /** Render type wrapping {@link #TRANSLUCENT_TRIANGLES} (no texture). */
  public static RenderType translucentTriangles() {
    return RenderType.create(
        "imf_translucent_triangles",
        RenderSetup.builder(TRANSLUCENT_TRIANGLES).createRenderSetup());
  }

  /** Render type wrapping {@link #OPAQUE_SCREEN} with the given texture. */
  public static RenderType opaqueScreen(Identifier texture) {
    return RenderType.create(
        "imf_opaque_screen",
        RenderSetup.builder(OPAQUE_SCREEN).withTexture("Sampler0", texture).createRenderSetup());
  }

  /** Render type wrapping {@link #ENTITY_THROUGH_WALLS} with the given texture. */
  public static RenderType entityThroughWalls(Identifier texture) {
    return RenderType.create(
        "imf_entity_through_walls",
        RenderSetup.builder(ENTITY_THROUGH_WALLS)
            .withTexture("Sampler0", texture)
            .sortOnUpload()
            .createRenderSetup());
  }
}
