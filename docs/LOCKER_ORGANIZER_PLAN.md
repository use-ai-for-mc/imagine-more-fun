# Locker Organizer Plan

## Current Observations

- ImagineFun exposes 8 lockers through `/pv 1` through `/pv 8`.
- Each locker is a 54-slot chest-like container. The lower 36 slots in the GUI are the player's inventory.
- Shulker-like containers, backpacks, buckets, suitcases, and similar items expose nested contents through `ItemStack` data components.
- The relevant Java path is:
  - `ItemStack.get(DataComponents.CONTAINER)`
  - `ItemContainerContents.asSlots()`
- Server `/lockersearch <query>` already searches across lockers and nested containers, then prints a tree to chat.
- The local exporter can preserve more information than `/lockersearch`: slot, path, item id, count, damage, max damage, and nested container trees.

## Design Direction

The organizer should treat containers as category homes. Lockers are physical storage space, not fixed category roles.

Important user constraints:

- Prefer clearing `Locker #1` so it can act as a staging or user workspace.
- Prefer packing stable storage into later lockers, roughly `#8` backward.
- Do not assign permanent roles to lockers.
- Allow categories to have multiple home containers.
- Prefer stable, local changes over a global re-sort.
- Container names matter, but renaming may require `Item Label (Right Click)`, so renaming should be suggested separately from moving.
- Before automatic movement, require the player's main inventory to be empty: 27 empty non-hotbar slots. The hotbar may remain occupied.
- The long-term storage target is that every non-hotbar item should live inside a shulker-like container rather than loose in a locker, except for explicitly reserved staging items.
- To avoid fighting the player, items already inside shulker-like containers are protected by default. The organizer may only change the contents of containers whose names match the managed naming rules.

## Robust Target Model

The target should be a set of stable constraints, not a complete sorted layout.

Examples:

- `Multipliers` can have a home chain: `Multipliers 01`, `Multipliers 02`, `Multipliers 03`.
- `Pins` can have a home chain: `Pins 01`, `Pins 02`, `Pins 03`.
- A new multiplier should enter the first available multiplier home.
- If all homes are full, the organizer creates or claims a stable overflow container.
- Already-correct items should not be moved.
- Generic containers such as `Popcorn Bucket` should keep their inferred role once assigned unless the evidence changes substantially.

This avoids sudden layout churn when one item is added, removed, or manually moved by the user.

## Managed Containers

The organizer only manages the inside of containers with explicit organizer names. Current managed names are category names followed by a two-digit number:

- `Pin Packs 01`
- `Pins 01`
- `Wearables 01`
- `Pets 01`
- `Multipliers 01`
- `Titles 01`
- `Trails Emotes Warp 01`
- `Food 01`
- `Art Albums Posters 01`
- `Toys Decor 01`
- `Wands Tools 01`
- `Misc 01`

Additional homes use the same prefix and the next number, such as `Pins 02` or `Wearables 02`.

Movement scope:

- Loose top-level locker items may be moved into a matching managed container.
- Items inside managed containers may be reorganized within the managed container set.
- Items inside unmanaged containers are read for reporting only and are not moved out automatically.
- Unmanaged containers may be moved as whole boxes only for locker packing or staging; their contents remain untouched.
- Generic names such as `Popcorn Bucket` are never managed until renamed or explicitly claimed by the user.

## Initial Categories

The first pass uses heuristic categories:

- `titles`
- `multipliers`
- `pins`
- `food_drinks`
- `hats_wearables`
- `pets_plushies`
- `tools_wands_weapons`
- `furniture_decor`
- `event_collectibles`
- `labels`
- `misc`

These are intentionally broad. The goal is to reduce scattering and make search easier, not to create a perfect taxonomy.

## Automation Phases

### Phase 1: Read-only export

- Open `/pv 1` through `/pv 8`.
- Read top-level locker slots.
- Recursively read nested containers from `DataComponents.CONTAINER`.
- Write a JSON snapshot to `build/`.

### Phase 2: Offline analysis

- Load the JSON snapshot.
- Infer item categories.
- Infer container roles from names and contents.
- Build home chains per category.
- Report:
  - repeated generic container names,
  - likely rename candidates,
  - scattered duplicate items,
  - `Locker #1` clearance candidates,
  - category capacity and overflow pressure.

### Phase 3: Simulated move planner

- Generate a transaction plan without clicking.
- Use stable home chains and minimal-diff moves.
- Keep `Locker #1` as the preferred staging area.
- Do not break existing good containers apart unless explicitly requested.
- Only plan moves for loose top-level items and items already inside managed containers.

### Phase 4: User-assisted extraction

This bridges the gap between protected player containers and full automation.

- The user prepares an empty managed target container, such as `Multipliers 01`.
- The planner lists at most 27 items to manually extract from unmanaged containers into the player's main inventory.
- The user performs the extraction, keeping the hotbar unchanged.
- The executor then moves the 27 main-inventory items into the managed target container.
- This preserves the hard rule that the organizer does not pull items out of unmanaged containers by itself.

### Phase 5: Controlled executor

- Execute one move at a time.
- Refuse to start unless the player's 27 main inventory slots are empty. This gives the executor a known scratch buffer while preserving the hotbar.
- Re-read source and target before each click.
- Re-read after each click.
- Wait for the server state id or visible slot state to settle after every click. Do not batch "pick up" and "place" clicks without an acknowledgement delay.
- Treat the menu carried stack as authoritative after server sync, even if an immediate client-side slot prediction briefly shows the item in its destination.
- Pause on mismatch, full target, missing item, user input, or screen change.
- Keep a transaction log.

## Safety Rules

- Never redeem or right-click consumable/redeemable items during organization.
- Do not move hotbar essentials or ride-related items unless explicitly allowed.
- Do not rely on a long precomputed plan after interruption. Re-scan and continue from current state.
- If a user manually moves an item, treat the new state as authoritative.
- Prefer moving whole containers before unpacking nested containers.
- Prefer ending with items inside containers. Loose locker slots are temporary staging, not the desired steady state.
- Do not move items out of unmanaged containers. They are protected player-owned organization, even if their contents look scattered.
- Rename suggestions are advisory until labels are available and the user authorizes use.
- Treat an open locker GUI as an unsafe transaction until it is closed and verified. Do not send `/pv <n>` for the same locker while the player still has that locker open. A locker persistence issue was observed and reported to server staff, so all organizer movement work stays blocked until the persistence path is verified safe again.

## Current Data Snapshot

The first successful snapshot in this workspace reported:

- 8 lockers
- 364 top-level locker items
- 2869 total items including nested contents
- 121 containers including nested containers
- 30 player inventory items visible from the final locker screen

This snapshot is stored under `build/` and is ignored by git.

## Live Movement Probe

With the player main inventory cleared, a reversible movement probe succeeded:

- `Locker #1 slot 2` -> `Locker #1 slot 1` -> `Locker #1 slot 2`
- `Locker #1 slot 2` -> player scratch slot `54` -> `Locker #1 slot 2`
- `Locker #1 slot 2` -> player scratch slot `54` -> `Locker #2 slot 33` -> player scratch slot `54` -> `Locker #1 slot 2`

The important implementation finding is that inventory clicks must be serialized. A rapid two-click script can leave the client showing a predicted destination while the server still has the item on the cursor. The executor should click once, wait for server state to settle, verify cursor/source/target, then issue the next click.
