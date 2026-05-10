package com.chenweikeng.imf.nra.simulator;

import com.chenweikeng.imf.ImfClient;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * A client-only "ghost" passenger. Subclasses {@link RemotePlayer} so the standard render pipeline
 * resolves its skin / cape via {@code Minecraft.getConnection().getPlayerInfo(uuid)} — that lookup
 * works only because {@link ProfileInjector} put a synthetic {@code PlayerInfo} in {@code
 * ClientPacketListener.playerInfoMap} first.
 *
 * <p>We override {@link #tick()} as a no-op: vanilla {@code RemotePlayer.tick()} chases server
 * positions that don't exist for ghosts and would log warnings. Position is driven manually by
 * {@link GhostManager}.
 */
public class GhostRider extends RemotePlayer {

  private final GhostSpec spec;

  public GhostRider(ClientLevel level, GhostSpec spec) {
    super(level, new GameProfile(spec.uuid(), spec.name()));
    this.spec = spec;
    this.noPhysics = true;
    setInvulnerable(true);
    setSilent(true);
  }

  public GhostSpec spec() {
    return spec;
  }

  /**
   * Apply the captured cosmetic items to this ghost. Must be called after the entity is added to a
   * level (we need the level's registry access for SNBT decoding).
   */
  public void applyEquipment() {
    if (spec.equipmentSnbt().isEmpty()) return;
    var registries = level().registryAccess();
    for (var entry : spec.equipmentSnbt().entrySet()) {
      EquipmentSlot slot = EquipmentSlot.byName(entry.getKey());
      if (slot == null) {
        ImfClient.LOGGER.warn(
            "GhostRider({}): unknown equipment slot '{}'", spec.name(), entry.getKey());
        continue;
      }
      ItemStack stack = ItemRecipe.fromSnbt(entry.getValue(), registries);
      if (!stack.isEmpty()) {
        setItemSlot(slot, stack);
        ImfClient.LOGGER.debug(
            "GhostRider({}): equipped {} = {}", spec.name(), slot.getName(), stack);
      }
    }
  }

  /** Maximum head deflection from the body, in degrees. Real Minecraft clamps to roughly this. */
  private static final float MAX_HEAD_TURN = 75f;

  @Override
  public void tick() {
    // Two cases:
    //   (a) Unmounted (free-standing): vanilla tickNonPassenger does NOT save old pos — we do it.
    //   (b) Mounted: ClientLevel.tickPassenger already called setOldPosAndRot before this tick,
    //       and the vehicle's positionRider will set our new pos AFTER this method returns. So
    //       we must NOT overwrite the old values.
    // Body/head rot interpolation is LivingEntity-specific; vanilla doesn't help with that, so we
    // sync those every tick regardless.
    if (!isPassenger()) {
      setOldPosAndRot();
    }

    // While riding, swivel the ghost's head toward the local player so they appear to be looking
    // at you. Body rotation is left alone — the seat dictates that, and we want the sit pose to
    // stay aligned with the cart.
    if (isPassenger()) {
      LocalPlayer target = Minecraft.getInstance().player;
      if (target != null) {
        lookHeadAt(target.getEyePosition());
      }
    }

    this.yBodyRotO = this.yBodyRot;
    this.yHeadRotO = this.yHeadRot;
  }

  private void lookHeadAt(Vec3 targetEye) {
    Vec3 myEye = getEyePosition();
    double dx = targetEye.x - myEye.x;
    double dy = targetEye.y - myEye.y;
    double dz = targetEye.z - myEye.z;
    double horiz = Math.sqrt(dx * dx + dz * dz);
    float wantYaw = (float) (Math.atan2(-dx, dz) * (180.0 / Math.PI));
    float wantPitch = (float) -(Math.atan2(dy, horiz) * (180.0 / Math.PI));

    // Clamp head yaw to ±MAX_HEAD_TURN relative to body so it doesn't twist past humanly possible.
    float yawDelta = Mth.wrapDegrees(wantYaw - this.yBodyRot);
    if (yawDelta > MAX_HEAD_TURN) wantYaw = this.yBodyRot + MAX_HEAD_TURN;
    else if (yawDelta < -MAX_HEAD_TURN) wantYaw = this.yBodyRot - MAX_HEAD_TURN;

    setYHeadRot(wantYaw);
    setXRot(Mth.clamp(wantPitch, -45f, 45f));
  }

  @Override
  public void aiStep() {
    // Same reason — RemotePlayer.aiStep is for server-driven animation interpolation.
  }

  @Override
  public void recreateFromPacket(ClientboundAddEntityPacket packet) {
    // We never get a real spawn packet for ghosts. Safe no-op.
  }
}
