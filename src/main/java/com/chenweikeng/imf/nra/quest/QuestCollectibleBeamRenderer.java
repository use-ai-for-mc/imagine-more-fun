package com.chenweikeng.imf.nra.quest;

import com.chenweikeng.imf.ImfClient;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
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
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

/** Renders a through-wall beacon beam above each positively identified quest collectible. */
public final class QuestCollectibleBeamRenderer {
  private static final double RENDER_DISTANCE = 300.0;
  private static final double RENDER_DISTANCE_SQUARED = RENDER_DISTANCE * RENDER_DISTANCE;
  private static final long COLOR_INTERVAL_MILLIS = 250L;
  private static final int RED_BEAM_COLOR = ARGB.color(255, 255, 0, 0);
  private static final int BLUE_BEAM_COLOR = ARGB.color(255, 0, 0, 255);

  private static final RenderPipeline BEAM_OPAQUE_PIPELINE =
      RenderPipelines.register(
          RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
              .withLocation(
                  Identifier.fromNamespaceAndPath(
                      ImfClient.MOD_ID, "pipeline/quest_collectible_beam_opaque"))
              .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
              .withDepthWrite(false)
              .build());

  private static final RenderPipeline BEAM_TRANSLUCENT_PIPELINE =
      RenderPipelines.register(
          RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
              .withLocation(
                  Identifier.fromNamespaceAndPath(
                      ImfClient.MOD_ID, "pipeline/quest_collectible_beam_translucent"))
              .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
              .withDepthWrite(false)
              .withBlend(BlendFunction.TRANSLUCENT)
              .build());

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
    WorldRenderEvents.AFTER_ENTITIES.register(QuestCollectibleBeamRenderer::render);
  }

  private static void render(WorldRenderContext context) {
    Minecraft client = Minecraft.getInstance();
    if (client.level == null || !NotRidingAlertClient.isImagineFunServer()) {
      return;
    }

    Vec3 camera = client.gameRenderer.getMainCamera().position();
    float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
    float animationTime = Math.floorMod(client.level.getGameTime(), 40L) + partialTick;
    int beamColor =
        Math.floorMod(System.currentTimeMillis() / COLOR_INTERVAL_MILLIS, 2L) == 0L
            ? RED_BEAM_COLOR
            : BLUE_BEAM_COLOR;
    PoseStack poseStack = context.matrices();
    SubmitNodeCollector collector = context.commandQueue();

    for (ArmorStand stand : QuestCollectibleGlow.markedStands()) {
      double dx = stand.getX() - camera.x;
      double dz = stand.getZ() - camera.z;
      if (dx * dx + dz * dz > RENDER_DISTANCE_SQUARED) {
        continue;
      }

      poseStack.pushPose();
      poseStack.translate(dx, -camera.y, dz);
      submitBeam(poseStack, collector, animationTime, (float) stand.getY() + 2.0F, beamColor);
      poseStack.popPose();
    }
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
