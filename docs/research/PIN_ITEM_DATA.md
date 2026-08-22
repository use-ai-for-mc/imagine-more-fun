# Pin Enhancement

> Observational research, not a product contract. Revalidate the schema against current live item
> data before changing parsers. The programmatic-unboxing notes document possibilities only and do
> not authorize adding automated gameplay behavior without an explicit user request.

This document describes the data structures found in ImagineFun pin items and how our mod can leverage them for enhanced features.

## Opened Pin Packs

Opened pin packs are items that contain embedded JSON data in their NBT/CustomData component. This data can be read by GUI mixins to display additional information or provide enhanced functionality.

### How to Access

```java
// Get CustomData component from ItemStack
CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
if (customData != null) {
    CompoundTag nbt = customData.copyTag();
    String packItem1Json = nbt.getString("PACK_ITEM_1").orElse(null);
    String packItem2Json = nbt.getString("PACK_ITEM_2").orElse(null);
    String pinPack = nbt.getString("PIN_PACK").orElse(null);
    boolean isOpened = nbt.getBoolean("IS_PACK_OPENED").orElse(false);
}
```

### NBT Structure

| Key | Type | Description |
|-----|------|-------------|
| `IS_PACK_OPENED` | boolean | Always `true` for opened packs |
| `PIN_PACK` | string | Series ID (e.g., `"TRASH_CAN"`) |
| `PACK_ITEM_1` | string (JSON) | First pin's full data |
| `PACK_ITEM_2` | string (JSON) | Second pin's full data |
| `PublicBukkitValues` | compound | Server tracking data |

### PACK_ITEM JSON Schema

Each `PACK_ITEM_N` contains a JSON string with the following structure:

```json
{
  "name": "§f§lSmall World Trash Can",
  "material": "NETHERITE_SWORD",
  "durability": 268,
  "series": {
    "id": "TRASH_CAN",
    "name": "Trash Can Series",
    "pricing": {
      "name": "&d&l&oPink",
      "color": "&d&l&o",
      "returnsMultiplier": 2.2,
      "cost": 500
    },
    "inShop": true,
    "pins": {
      "total": 128
    },
    "seriesId": "TRASH_CAN"
  },
  "rarity": {
    "id": "COMMON",
    "description": "§7Common",
    "chance": 16,
    "resellersScore": 1
  },
  "registryId": "pin/trashcans/trash_1",
  "condition": {
    "id": "SLIGHTLY_SCRATCHED",
    "name": "&fSlightly Scratched",
    "chance": 8
  }
}
```

### Key Fields for GUI Enhancement

#### Pin Identification
| Field | Description |
|-------|-------------|
| `name` | Display name with color codes (§ format) |
| `registryId` | Internal ID like `pin/trashcans/trash_1` |
| `durability` | Maps to custom model data (texture selection) |
| `material` | Base Minecraft item type |

#### Series Information
| Field | Description |
|-------|-------------|
| `series.id` | Series identifier (e.g., `TRASH_CAN`) |
| `series.name` | Human-readable series name |
| `series.pricing.cost` | Pack purchase price |
| `series.pricing.returnsMultiplier` | Value multiplier for reselling |
| `series.pins.total` | Total pins in the series |
| `series.inShop` | Whether series is currently available |

#### Rarity Information
| Field | Description |
|-------|-------------|
| `rarity.id` | Rarity tier: `COMMON`, `UNCOMMON`, `RARE`, `EPIC`, `LEGENDARY` |
| `rarity.description` | Formatted rarity text |
| `rarity.chance` | Drop chance weight (higher = more common) |
| `rarity.resellersScore` | Trade value score (1=Common, 2=Uncommon, etc.) |

#### Condition Information
| Field | Description |
|-------|-------------|
| `condition.id` | Condition identifier |
| `condition.name` | Formatted condition text (& color codes) |
| `condition.chance` | Condition rarity (lower = rarer) |

**Known Conditions:**
| ID | Name | Chance |
|----|------|--------|
| `BRAND_NEW_MINT_CONDITION` | Brand New Mint Condition | 3 |
| `HEAVILY_WORN` | Heavily Worn | 5 |
| `SLIGHTLY_SCRATCHED` | Slightly Scratched | 8 |
| `WELL_KEPT` | Well Kept | 10 |

### Server Tracking (PublicBukkitValues)

```json
{
  "imaginefun:big-brother-item-id": "6a4d0487-a9ce-423c-93e6-8fbbe5d89ad9",
  "imaginefun:big-brother-item-original-owner": 15124092,
  "imaginefun:big-brother-item-server-origin": "disneyland1"
}
```

## Potential GUI Enhancements

1. **Pin Pack Preview** - Show contained pins before opening in a custom tooltip or overlay
2. **Condition Indicator** - Display condition quality with color-coded icons
3. **Value Calculator** - Calculate estimated trade value based on rarity and condition
4. **Collection Tracker** - Cross-reference with player's pin book to show "already owned" status
5. **Series Progress** - Show completion percentage for the series

## Unopened Pin Packs

Unopened packs have minimal data - only the series ID and server tracking. Pin contents are determined server-side when opened.

### NBT Structure (Unopened)

```json
{
  "PIN_PACK": "EPCOT",
  "PublicBukkitValues": {
    "imaginefun:big-brother-item-id": "6a21a2a1-c912-49fb-8e6d-94d5f40dffd1",
    "imaginefun:big-brother-item-original-owner": 15124092,
    "imaginefun:big-brother-item-server-origin": "disneyland2"
  }
}
```

**Key differences:**
| Unopened | Opened |
|----------|--------|
| Base item: `diamond_hoe` | Base item: `netherite_sword` |
| Only has `PIN_PACK` | Has `PIN_PACK`, `PACK_ITEM_1`, `PACK_ITEM_2` |
| No `IS_PACK_OPENED` | `IS_PACK_OPENED: true` |
| No pin details | Full pin JSON with name, rarity, condition |

## Star (⭐) Indicator

**Verified:** The `⭐` prefix in the display name indicates the pack contains at least one pin with `BRAND_NEW_MINT_CONDITION`. It is NOT related to rarity (uncommon+).

| Pack Type | Has ⭐ | Has Mint Condition |
|-----------|--------|-------------------|
| Star packs | ✅ | ✅ Always |
| Non-star packs | ❌ | ❌ Never |

To check programmatically:
```java
String nbtString = customData.toString();
boolean hasMintCondition = nbtString.contains("BRAND_NEW_MINT_CONDITION");
// This correlates 100% with the ⭐ prefix
```

## Programmatic Unboxing

Pin packs can be unboxed programmatically without user interaction.

### Method 1: Inventory Screen (QUICK_MOVE)

When the inventory screen is open, use shift-click (QUICK_MOVE) on the slot:

```java
// containerId = 0 for player inventory
// slotNum = the slot containing the pin pack
// button = 0 (left click)
// ContainerInput.QUICK_MOVE = shift+click
mc.gameMode.handleContainerInput(0, slotNum, 0, ContainerInput.QUICK_MOVE, player);
```

This triggers the server's "Shift Click to unbox" behavior.

### Method 2: Hotbar Item (Use Item)

When the item is in the hotbar and selected:

```java
// 1. Select the slot
player.getInventory().selected = slotNum;

// 2. Set shift state
player.setShiftKeyDown(true);

// 3. Use the item
mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);

// 4. Reset shift state
player.setShiftKeyDown(false);
```

### Method 3: Direct Packet (Most Control)

Send packets directly for full control:

```java
// 1. Send shift input state
Input shiftInput = new Input(false, false, false, false, false, true, false);
connection.send(new ServerboundPlayerInputPacket(shiftInput));

// 2. Send use item or container click packet
// ...

// 3. Reset shift input state
Input noShiftInput = new Input(false, false, false, false, false, false, false);
connection.send(new ServerboundPlayerInputPacket(noShiftInput));
```

## Implementation Notes

- Pin packs use `NETHERITE_SWORD` (opened) or `DIAMOND_HOE` (unopened) as base item
- Custom durability maps to texture selection
- Color codes use both `§` (Minecraft) and `&` (legacy Bukkit) formats
- The `⭐` prefix indicates `BRAND_NEW_MINT_CONDITION`, not rarity
- Data is only present in **opened** packs; unopened packs don't have `PACK_ITEM_*` fields

## Related Files

- `com.chenweikeng.imf.pim.screen.PinDetailHandler` - Handles pin detail data
- `com.chenweikeng.imf.pim.screen.PinBookHandler` - Handles pin book/collection data
- `com.chenweikeng.imf.pim.screen.PinRarityHandler` - Handles rarity information
