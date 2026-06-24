/*
 * Read-only preflight for future locker movement tests.
 *
 * Requirement:
 * - The 27 main inventory slots (player inventory indexes 9..35) must be empty.
 * - Hotbar slots (0..8) may remain occupied.
 * - Cursor/carried stack must be empty.
 *
 * Note: this is only a stable-state preflight. During actual movement, each
 * click must be followed by a server-sync wait and a fresh slot/cursor read.
 */

def result = sync {
  def inventoryItems = java.list(player.inventory.items)
  def mainNonEmpty = []
  def hotbarNonEmpty = []

  for (int slot = 9; slot <= 35; slot++) {
    def stack = inventoryItems[slot]
    if (!stack.empty) {
      mainNonEmpty << [
        inventorySlot: slot,
        name: stack.hoverName.string,
        count: stack.count
      ]
    }
  }

  for (int slot = 0; slot <= 8; slot++) {
    def stack = inventoryItems[slot]
    if (!stack.empty) {
      hotbarNonEmpty << [
        inventorySlot: slot,
        name: stack.hoverName.string,
        count: stack.count
      ]
    }
  }

  def carried = player.containerMenu.carried

  return [
    okToMove: mainNonEmpty.isEmpty() && carried.empty,
    mainInventoryEmptySlots: 27 - mainNonEmpty.size(),
    mainInventoryNonEmptySlots: mainNonEmpty.size(),
    hotbarNonEmptySlots: hotbarNonEmpty.size(),
    cursorEmpty: carried.empty,
    cursorItem: carried.empty ? null : [
      name: carried.hoverName.string,
      count: carried.count
    ],
    blockers: mainNonEmpty,
    hotbarItems: hotbarNonEmpty
  ]
}

return result
