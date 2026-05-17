package com.chenweikeng.imf.nra.spacemountain;

import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;

/**
 * Whitelist of armor-stand head items whose entities are hidden entirely (not rendered) while the
 * player is riding Space or Hyperspace Mountain. ImagineFun renders show props as armor stands
 * wearing custom-modelled items in the helmet slot — the {@code (itemId, damage)} pair identifies
 * the specific custom model. Anything not on this list renders normally.
 *
 * <p>Add entries as you identify them in-game with {@code mc_entity_details} on the looked-at
 * entity (the HEAD field's {@code itemId} and {@code damage}).
 */
public final class SpaceMountainEntityHider {
  /** ({@code minecraft:item_id}, custom-model damage value). */
  public record HelmetSignature(String itemId, int damage) {}

  private static final Set<HelmetSignature> WHITELIST =
      Set.of(
          // TIE Fighter Shoulder Pet
          new HelmetSignature("minecraft:diamond_sword", 145),
          // X-Wing Shoulder Pet
          new HelmetSignature("minecraft:diamond_sword", 143));

  private SpaceMountainEntityHider() {}

  /** True when {@code stand}'s head slot holds a whitelisted custom-modelled item. */
  public static boolean shouldHide(ArmorStand stand) {
    ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
    if (head.isEmpty()) return false;
    String itemId = BuiltInRegistries.ITEM.getKey(head.getItem()).toString();
    return WHITELIST.contains(new HelmetSignature(itemId, head.getDamageValue()));
  }
}
