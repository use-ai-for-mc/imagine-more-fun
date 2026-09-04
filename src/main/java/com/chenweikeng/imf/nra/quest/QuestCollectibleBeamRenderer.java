package com.chenweikeng.imf.nra.quest;

import com.chenweikeng.imf.ImfClient;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Renders one through-wall beacon beam from the collectible dust centroid. */
public final class QuestCollectibleBeamRenderer {
  private static final double RENDER_DISTANCE = 300.0;
  private static final double RENDER_DISTANCE_SQUARED = RENDER_DISTANCE * RENDER_DISTANCE;
  private static final long COLOR_INTERVAL_MILLIS = 250L;
  private static final int RED_BEAM_COLOR = ARGB.color(255, 255, 0, 0);
  private static final int BLUE_BEAM_COLOR = ARGB.color(255, 0, 0, 255);

  private static final RenderPipeline BEAM_OPAQUE_PIPELINE =
      throughWallPipeline(RenderPipelines.BEACON_BEAM_OPAQUE, "quest_collectible_beam_opaque");
  private static final RenderPipeline BEAM_TRANSLUCENT_PIPELINE =
      throughWallPipeline(
          RenderPipelines.BEACON_BEAM_TRANSLUCENT, "quest_collectible_beam_translucent");

  /** Retains vanilla's beacon shaders and bindings while disabling depth tests and writes. */
  private static RenderPipeline throughWallPipeline(RenderPipeline vanilla, String name) {
    RenderPipeline.Builder builder =
        RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(ImfClient.MOD_ID, "pipeline/" + name))
            .withVertexShader(vanilla.getVertexShader())
            .withFragmentShader(vanilla.getFragmentShader())
            .withVertexBinding(0, vanilla.getVertexFormatBinding(0))
            .withPrimitiveTopology(vanilla.getPrimitiveTopology())
            .withCull(vanilla.isCull())
            .withPolygonMode(vanilla.getPolygonMode())
            .withColorTargetState(vanilla.getColorTargetState())
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false));
    vanilla.getBindGroupLayouts().forEach(builder::withBindGroupLayout);
    // Vanilla beacon pipelines have no shader defines in 26.2.
    return builder.build();
  }

  private static final RenderType BEAM_OPAQUE_TYPE =
      RenderType.create(
          "imf_quest_collectible_beam_opaque",
          RenderSetup.builder(BEAM_OPAQUE_PIPELINE)
              .withTexture("Sampler0", BeaconRenderer.BEAM_LOCATION)
              .sortOnUpload()
              .createRenderSetup());

  private static final RenderType BEAM_TRANSLUCENT_TYPE =
      RenderType.create(
          "imf_quest_collectible_beam_translucent",
          RenderSetup.builder(BEAM_TRANSLUCENT_PIPELINE)
              .withTexture("Sampler0", BeaconRenderer.BEAM_LOCATION)
              .sortOnUpload()
              .createRenderSetup());

  private QuestCollectibleBeamRenderer() {}

  public static void register() {
    LevelRenderEvents.COLLECT_SUBMITS.register(QuestCollectibleBeamRenderer::render);
  }

  private static void render(LevelRenderContext context) {
    Minecraft client = Minecraft.getInstance();
    Vec3 origin = QuestCollectibleGlow.beamOrigin();
    if (origin == null || client.level == null) {
      return;
    }

    Vec3 camera = context.levelState().cameraRenderState.pos;
    double dx = origin.x - camera.x;
    double dz = origin.z - camera.z;
    if (dx * dx + dz * dz > RENDER_DISTANCE_SQUARED) {
      return;
    }

    float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
    float animationTime = Math.floorMod(client.level.getGameTime(), 40L) + partialTick;
    int beamColor =
        Math.floorMod(System.currentTimeMillis() / COLOR_INTERVAL_MILLIS, 2L) == 0L
            ? RED_BEAM_COLOR
            : BLUE_BEAM_COLOR;
    PoseStack poseStack = context.poseStack();
    poseStack.pushPose();
    poseStack.translate(dx, -camera.y, dz);
    submitBeam(
        poseStack, context.submitNodeCollector(), animationTime, (float) origin.y, beamColor);
    poseStack.popPose();
  }

  /** Reimplementation of the vanilla beacon geometry using no-depth-test render pipelines. */
  private static void submitBeam(
      PoseStack poseStack,
      SubmitNodeCollector collector,
      float animationTime,
      float beamStart,
      int beamColor) {
    float beamEnd = 320.0F;
    float height = beamEnd - beamStart;
    float solidRadius = 0.2F;
    float glowRadius = 0.25F;
    float scroll = -animationTime;
    float textureOffset = Mth.frac(scroll * 0.2F - Mth.floor(scroll * 0.1F));

    poseStack.pushPose();
    poseStack.mulPose(Axis.YP.rotationDegrees(animationTime * 2.25F - 45.0F));
    float innerV2 = -1.0F + textureOffset;
    float innerV1 = height * (0.5F / solidRadius) + innerV2;
    collector.submitCustomGeometry(
        poseStack,
        BEAM_OPAQUE_TYPE,
        (pose, buffer) ->
            renderPart(
                pose,
                buffer,
                beamColor,
                beamStart,
                beamEnd,
                0.0F,
                solidRadius,
                solidRadius,
                0.0F,
                -solidRadius,
                0.0F,
                0.0F,
                -solidRadius,
                0.0F,
                1.0F,
                innerV1,
                innerV2));
    poseStack.popPose();

    float outerV2 = -1.0F + textureOffset;
    float outerV1 = height + outerV2;
    collector.submitCustomGeometry(
        poseStack,
        BEAM_TRANSLUCENT_TYPE,
        (pose, buffer) ->
            renderPart(
                pose,
                buffer,
                ARGB.color(32, beamColor),
                beamStart,
                beamEnd,
                -glowRadius,
                -glowRadius,
                glowRadius,
                -glowRadius,
                -glowRadius,
                glowRadius,
                glowRadius,
                glowRadius,
                0.0F,
                1.0F,
                outerV1,
                outerV2));
  }

  private static void renderPart(
      PoseStack.Pose pose,
      VertexConsumer buffer,
      int color,
      float beamStart,
      float beamEnd,
      float westNorthX,
      float westNorthZ,
      float eastNorthX,
      float eastNorthZ,
      float westSouthX,
      float westSouthZ,
      float eastSouthX,
      float eastSouthZ,
      float minU,
      float maxU,
      float maxV,
      float minV) {
    renderQuad(
        pose,
        buffer,
        color,
        beamStart,
        beamEnd,
        westNorthX,
        westNorthZ,
        eastNorthX,
        eastNorthZ,
        minU,
        maxU,
        maxV,
        minV);
    renderQuad(
        pose,
        buffer,
        color,
        beamStart,
        beamEnd,
        eastSouthX,
        eastSouthZ,
        westSouthX,
        westSouthZ,
        minU,
        maxU,
        maxV,
        minV);
    renderQuad(
        pose,
        buffer,
        color,
        beamStart,
        beamEnd,
        eastNorthX,
        eastNorthZ,
        eastSouthX,
        eastSouthZ,
        minU,
        maxU,
        maxV,
        minV);
    renderQuad(
        pose,
        buffer,
        color,
        beamStart,
        beamEnd,
        westSouthX,
        westSouthZ,
        westNorthX,
        westNorthZ,
        minU,
        maxU,
        maxV,
        minV);
  }

  private static void renderQuad(
      PoseStack.Pose pose,
      VertexConsumer buffer,
      int color,
      float beamStart,
      float beamEnd,
      float x1,
      float z1,
      float x2,
      float z2,
      float minU,
      float maxU,
      float maxV,
      float minV) {
    buffer
        .addVertex(pose, x1, beamEnd, z1)
        .setColor(color)
        .setUv(maxU, maxV)
        .setOverlay(OverlayTexture.NO_OVERLAY)
        .setLight(15728880)
        .setNormal(pose, 0.0F, 1.0F, 0.0F);
    buffer
        .addVertex(pose, x1, beamStart, z1)
        .setColor(color)
        .setUv(maxU, minV)
        .setOverlay(OverlayTexture.NO_OVERLAY)
        .setLight(15728880)
        .setNormal(pose, 0.0F, 1.0F, 0.0F);
    buffer
        .addVertex(pose, x2, beamStart, z2)
        .setColor(color)
        .setUv(minU, minV)
        .setOverlay(OverlayTexture.NO_OVERLAY)
        .setLight(15728880)
        .setNormal(pose, 0.0F, 1.0F, 0.0F);
    buffer
        .addVertex(pose, x2, beamEnd, z2)
        .setColor(color)
        .setUv(minU, maxV)
        .setOverlay(OverlayTexture.NO_OVERLAY)
        .setLight(15728880)
        .setNormal(pose, 0.0F, 1.0F, 0.0F);
  }
}
