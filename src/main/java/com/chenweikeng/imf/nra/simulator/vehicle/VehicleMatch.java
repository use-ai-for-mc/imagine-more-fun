package com.chenweikeng.imf.nra.simulator.vehicle;

/**
 * Recognition criterion: an armor stand "is" a particular ride vehicle if its head-slot item
 * matches both the {@code itemId} and {@code damage} value.
 *
 * <p>ImagineFun uses {@code (itemId, damage)} as a resource-pack model selector, so different
 * decoration stands wearing the same base item with different damage values map to visually
 * distinct cart props. That makes the pair a stable identifier per vehicle type.
 */
public record VehicleMatch(String itemId, int damage) {

  public boolean matches(String stackItemId, int stackDamage) {
    return itemId.equals(stackItemId) && damage == stackDamage;
  }
}
