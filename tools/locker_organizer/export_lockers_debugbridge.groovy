/*
 * Paste this script into mc_execute to export lockers with nested contents.
 *
 * It is read-only with respect to items: it opens /pv 1 through /pv 8, reads
 * ItemStack data, and writes JSON under build/. It does not click or move any
 * inventory slots.
 */

def BuiltInRegistries = java.type('net.minecraft.core.registries.BuiltInRegistries')
def DataComponents = java.type('net.minecraft.core.component.DataComponents')
def GsonBuilder = java.type('com.google.gson.GsonBuilder')
def FileCls = java.type('java.io.File')
def FileOutputStream = java.type('java.io.FileOutputStream')
def Instant = java.type('java.time.Instant')

def idOf = { stack ->
  BuiltInRegistries.ITEM.getKey(stack.item).toString()
}

def serializeStack
serializeStack = { stack, slotIndex, path, depth ->
  def out = [
    slot: slotIndex,
    path: path,
    itemId: idOf(stack),
    count: stack.count,
    name: stack.hoverName.string,
    damage: stack.damageValue,
    maxDamage: stack.maxDamage
  ]

  def container = stack.get(DataComponents.CONTAINER)
  if (!java.isNull(container)) {
    def children = []
    if (depth < 8) {
      java.list(container.asSlots()).each { childSlot ->
        children << serializeStack(
          childSlot.item(),
          childSlot.index(),
          path + '/container/' + childSlot.index(),
          depth + 1
        )
      }
    }

    out.container = [
      type: java.typeName(container),
      nonEmptySlots: children.size(),
      truncated: depth >= 8,
      items: children
    ]
  }

  return out
}

def readOpenLocker = { int lockerNumber ->
  sync {
    def menu = player.containerMenu
    def items = []

    java.list(menu.slots).take(54).each { slot ->
      if (!slot.item.empty) {
        items << serializeStack(
          slot.item,
          slot.index,
          'locker/' + lockerNumber + '/slot/' + slot.index,
          0
        )
      }
    }

    return [
      locker: lockerNumber,
      title: mc.screen == null ? null : mc.screen.title.string,
      menuClass: java.typeName(menu),
      topLevelSlots: 54,
      nonEmptyTopLevelSlots: items.size(),
      items: items
    ]
  }
}

def countAll
countAll = { items ->
  int total = 0
  items.each { item ->
    total += 1
    if (item.container != null) {
      total += countAll(item.container.items)
    }
  }
  return total
}

def countContainers
countContainers = { items ->
  int total = 0
  items.each { item ->
    if (item.container != null) {
      total += 1
      total += countContainers(item.container.items)
    }
  }
  return total
}

def lockers = []
for (int lockerNumber = 1; lockerNumber <= 8; lockerNumber++) {
  def command = ('pv ' + lockerNumber).toString()
  sync {
    player.connection.sendCommand(command)
  }
  Thread.sleep(800)
  lockers << readOpenLocker(lockerNumber)
}

def playerInventory = sync {
  def invItems = []
  java.list(player.containerMenu.slots).drop(54).each { slot ->
    if (!slot.item.empty) {
      invItems << serializeStack(
        slot.item,
        slot.index,
        'playerInventory/menuSlot/' + slot.index,
        0
      )
    }
  }
  return [menuSlotOffset: 54, items: invItems]
}

int totalTop = 0
int totalItems = 0
int totalContainers = 0
lockers.each { locker ->
  totalTop += locker.items.size()
  totalItems += countAll(locker.items)
  totalContainers += countContainers(locker.items)
}

def generatedAt = Instant.now().toString()
def export = [
  schemaVersion: 1,
  generatedAt: generatedAt,
  source: [
    method: 'DebugBridge MCP mc_execute using Minecraft Java APIs',
    commands: (1..8).collect { '/pv ' + it },
    readOnly: true
  ],
  player: [name: player.name.string],
  summary: [
    lockers: lockers.size(),
    topLevelItems: totalTop,
    totalItemsIncludingNested: totalItems,
    containersIncludingNested: totalContainers,
    playerInventoryItems: playerInventory.items.size()
  ],
  lockers: lockers,
  playerInventory: playerInventory
]

def json = GsonBuilder().setPrettyPrinting().create().toJson(export)
def dir = FileCls('/Users/cusgadmin/if-local/imf/build')
dir.mkdirs()
def safeTs = generatedAt.replace(':', '-').replace('.', '-')
def path = '/Users/cusgadmin/if-local/imf/build/locker-export-' + safeTs + '.json'
def out = FileOutputStream(path)
out.write(json.getBytes('UTF-8'))
out.close()

return [path: path, bytes: FileCls(path).length(), summary: export.summary]
