package com.chenweikeng.imf.nra.simulator;

import com.chenweikeng.imf.ImfClient;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.DataResult;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Parses captured SNBT into a runtime {@link ItemStack}, stripping ImagineFun's server-side
 * tracking tags so we don't accidentally clone real items' identity.
 */
public final class ItemRecipe {

  private ItemRecipe() {}

  /**
   * @return the parsed stack, or {@link ItemStack#EMPTY} on parse failure (logged).
   */
  public static ItemStack fromSnbt(String snbt, HolderLookup.Provider registries) {
    if (snbt == null || snbt.isEmpty()) return ItemStack.EMPTY;
    CompoundTag tag;
    try {
      tag = TagParser.parseCompoundFully(snbt);
    } catch (CommandSyntaxException e) {
      ImfClient.LOGGER.error("ItemRecipe: SNBT parse failed: {}", e.getMessage());
      return ItemStack.EMPTY;
    }
    RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
    DataResult<ItemStack> result = ItemStack.CODEC.parse(ops, tag);
    ItemStack stack = result.result().orElse(ItemStack.EMPTY);
    if (stack.isEmpty()) {
      ImfClient.LOGGER.warn(
          "ItemRecipe: codec returned empty stack — error: {}",
          result.error().map(e -> e.message()).orElse("(none)"));
      return ItemStack.EMPTY;
    }
    stripPublicBukkitValues(stack);
    return stack;
  }

  /**
   * ImagineFun stamps every cosmetic with {@code PublicBukkitValues} (anti-dupe / origin tracking)
   * inside the {@code minecraft:custom_data} component. Strip it from our recreations so we don't
   * carry the real item's identity around — it has no client-visible effect anyway.
   */
  private static void stripPublicBukkitValues(ItemStack stack) {
    CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
    if (cd == null) return;
    CompoundTag copy = cd.copyTag();
    if (copy.contains("PublicBukkitValues")) {
      copy.remove("PublicBukkitValues");
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(copy));
    }
  }
}
