package com.chenweikeng.imf.nra.handler;

import com.chenweikeng.imf.nra.GameState;
import com.chenweikeng.imf.nra.NotRidingAlertClient;
import com.chenweikeng.imf.nra.config.ModConfig;
import com.chenweikeng.imf.nra.ride.AutograbHolder;
import com.chenweikeng.imf.nra.ride.AutograbHolder.Point;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

public class AutograbRegionRenderer {
  private static final double RENDER_DISTANCE = 50.0;

  public static void register() {
    LevelRenderEvents.COLLECT_SUBMITS.register(
        context -> {
          if (!NotRidingAlertClient.isImagineFunServer()) {
            return;
          }
          render(context);
        });
  }

  public static void render(LevelRenderContext context) {
    if (!ModConfig.currentSetting.showAutograbRegions) {
      return;
    }

    if (GameState.getInstance().isRiding()) {
      return;
    }

    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null) {
      return;
    }

    PoseStack poseStack = context.poseStack();
    Vec3 cam = context.levelState().cameraRenderState.pos;

    for (AutograbHolder.AutograbRegion region : AutograbHolder.regions()) {
      if (!region.filter().test(mc)) {
        continue;
      }

      double dx = cam.x - region.center().x;
      double dz = cam.z - region.center().z;
      double distance = Math.sqrt(dx * dx + dz * dz);

      if (distance <= RENDER_DISTANCE) {
        context
            .submitNodeCollector()
            .submitCustomGeometry(
                poseStack,
                RenderTypes.debugTriangleFan(),
                (pose, buffer) -> drawRegion(buffer, pose, cam, region));
      }
    }
  }

  private static void drawRegion(
      VertexConsumer buffer, PoseStack.Pose pose, Vec3 cam, AutograbHolder.AutograbRegion region) {
    Point[] points = region.points();
    double y = region.y();
    if (points.length < 2) {
      return;
    }

    for (int i = 0; i < points.length; i++) {
      Point p1 = points[i];
      Point p2 = points[(i + 1) % points.length];

      buffer
          .addVertex(
              pose, (float) (p1.x - cam.x), (float) (y + 0.275 - cam.y), (float) (p1.z - cam.z))
          .setColor(0f, 0.8f, 0f, 0.3f)
          .setNormal(pose, 0.0f, 1.0f, 0.0f)
          .setLineWidth(1.0f);

      buffer
          .addVertex(
              pose, (float) (p2.x - cam.x), (float) (y + 0.275 - cam.y), (float) (p2.z - cam.z))
          .setColor(0f, 0.8f, 0f, 0.3f)
          .setNormal(pose, 0.0f, 1.0f, 0.0f)
          .setLineWidth(1.0f);
    }
  }
}
