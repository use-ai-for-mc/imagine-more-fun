# Daily Ride Plan — implementation roadmap

Living document. Tick stages off as they ship; revise scope based on in-game feedback.

Design reference: `/Users/cusgadmin/Downloads/compass_artifact_wf-ccdf816b-1bc7-4a31-baab-3498979a227e_text_markdown.md` (not checked into the repo).

## Decisions locked in

- **Seed**: fresh random on first generation of each local day. Not date-hashed, not shared.
- **Credit model**: prospective-only. Snapshot ride counts at generation time; nodes credit only against the delta since.
- **Scope**: purely local, single-player's own goals. No UUID suffix, no per-profile split — one file at `config/imaginemorefun/nra-daily-plan.json`.
- **Reset**: local system midnight, matching `/ridereport` (`LocalDate.now()`).
- **Filters** (same as strategy HUD): honor `hiddenRides`, `onlyAutograbbing`, exclude rides at or past `maxGoal`.

## Stages

### Stage 1 — Generator + chat display ✅ shipped (0629029)
- [x] `ImfStorage.nraDailyPlan()` path
- [x] `DailyPlan` / `DailyPlanNode` POJOs (with `completed` field reserved for Stage 2)
- [x] `DailyPlanStorage` load/save
- [x] `DailyPlanGenerator` — 5 singles, k=2, random distinct rides from filtered pool
- [x] `DailyPlanManager` — get-or-create today's plan, regenerate on date roll
- [x] `/rideplan` command prints tree in chat
- [x] `DailyPlanChatRenderer` — colored ASCII list *(superseded: chat display replaced by HUD in Stages 3+; class deleted)*

**Done when**: `/rideplan` shows 5 random rides with ×2 next to each; returns same plan across relog within the day; new plan next day.

**How to test Stage 1**
1. Launch ImagineFun via Modrinth launcher; the new JAR is already deployed.
2. Join any world/server — `/rideplan` is client-only and not gated to ImagineFun.
3. Run `/rideplan`. Expect: gold divider, "Today's Ride Plan" header with date, 5 numbered rides with `×2` in cyan.
4. Run `/rideplan` again. Expect: **identical** 5 rides (same file, same plan).
5. Inspect `~/Library/Application Support/ModrinthApp/profiles/ImagineFun/config/imaginemorefun/nra-daily-plan.json` — should contain today's ISO date, epoch ms, a full `snapshotCounts` map, and 5-node list.
6. Toggle `onlyAutograbbing` on in the mod config, **delete the plan file**, run `/rideplan`. Expect: rides drawn only from the autograbbing set.
7. Roll the day: either delete the file or advance system clock past midnight, run `/rideplan`. Expect: fresh 5 rides and a new date in the file.
8. Edge case: set `maxGoal` low, ride everything past it, delete plan. Expect: header says "No eligible rides".

### Stage 2 — Auto-completion + Tier-1 stamps ✅ shipped
- [x] Hook `RideCountManager` delta detection (per-tick poll from `NotRidingAlertClient.onClientTick`)
- [x] Mark nodes complete when delta ≥ k
- [x] On node complete: chat line + `happy_villager` particles + `note_block.bell`
- [x] Update `/rideplan` output to show ● / ◐ / ○ with live `m/k` badge
- [x] Persist completion state (saved to `nra-daily-plan.json` on change)
- [x] Plan-complete flourish: level-up sound + double particles + "Daily Ride Plan complete!" line

**How to test Stage 2**
1. Launch ImagineFun with the new JAR.
2. Run `/rideplan` to see today's plan (any already-completed nodes will render with `●`).
3. Ride one of the listed rides. Expect after each completion increment:
   - Chat: `✨ [IMF] Node complete! <ride name> (2/5)`
   - Note-block bell sound
   - Burst of 12 green `happy_villager` particles around your head
4. Run `/rideplan` again — that node now renders with `●` in green, others still `○`.
5. Complete all 5 nodes → expect extra "Daily Ride Plan complete!" line + level-up sound + 24 particles.
6. Relog mid-way → completed nodes persist; progress on incomplete nodes comes from live count delta against the saved snapshot.

### Stage 3 — Top-of-screen tree HUD ✅ shipped
- [x] Register `imaginemorefun:daily_plan` HUD layer (attached before CHAT, same as strategy)
- [x] Horizontal left-to-right chain at top of screen: title + centered node chain
- [x] Each node = glyph (●/◐/○) + ride short name + `m/k` progress, connectors `──` between
- [x] Replaces the strategy HUD while active (strategy dispatcher early-returns when plan HUD is active)
- [x] Config toggle `showDailyPlanHud` (default on) under General
- [x] Respects existing `trackerDisplayMode` + boss-bar suppression (inherits strategy HUD's gating)

### Stage 3.5 — Tree-like boxes + backdrop ✅ shipped
- [x] Semi-transparent panel (`0xB0000000`) behind the whole HUD for contrast against gameplay
- [x] Each node rendered as a bordered box (filled `0x60000000` + 1px border in status color)
- [x] Two rows inside each box: `● NAME` top, `m/k` bottom, both centered
- [x] Proper horizontal connector lines (2px thick, drawn via `hLine`) between node boxes
- [x] Connector line colored green when the source node is complete — "energized skill-tree" feel

### Stage 3.6 — Riding countdown row ✅ shipped
- [x] When riding (`CurrentRideHolder.getCurrentRide() != null`), render a row between title and chain: `▶ <ride> · <pct>% · <m s> left` in `trackerRidingColor`
- [x] When autograbbing (autograb ride at location & not yet a passenger), render `⟲ Autograbbing <ride>…` in `trackerAutograbbingColor`
- [x] Panel height grows automatically when the row is present, shrinks back when not

**How to test Stage 3**
1. Join ImagineFun with new JAR.
2. Expect top-of-screen row: `✨ Ride Plan · Thu Apr 23 · 0/5` (gold), below it a centered chain `○ ALICE 0/2 ── ○ BTM 0/2 ──` … with untouched nodes in gray.
3. Ride one ride once → chain updates: `◐ ALICE 1/2` in orange. Ride twice → bell + particles fire, chain line flips `● ALICE ×2` in green.
4. Strategy HUD should no longer be visible while plan HUD is showing.
5. Open Cloth config → General → toggle "Show Daily Ride Plan HUD" off → strategy HUD returns, plan HUD vanishes.
6. Summon a boss (or any vanilla boss bar) → plan HUD hides cleanly.
7. `trackerDisplayMode=ONLY_WHEN_RIDING` → plan HUD hides when not riding, mirroring strategy behavior.
8. All 5 nodes done → title flips green, chain is all-green `●`s.

### Stage 4.8 — Power-transmission connector + quieter chat ✅ shipped
- [x] Only the **prior → active** connector animates: a 5-pixel white-cyan spark sweeps left → right over the 1.5-second period on top of the static green trail
- [x] Works when the active layer is the left-edge of the sliding window (animates the left-ellipsis connector instead)
- [x] All other connectors (done→done, active→gated, right-ellipsis) stay static
- [x] Removed chat messages from `DailyPlanCelebration.nodeCompleted` and `layerCompleted`; sound + particles remain

### Stage 4.7 — Blink active-ride node ✅ shipped
- [x] When the player is on (or autograbbing) a ride that matches an uncompleted node in the **active** layer, that node's border pulses blue (1 sec sine, dim → bright cyan)
- [x] Applies to HUD and full-window Screen
- [x] Future-layer nodes never blink (gating still rules)

### Stage 4.6 — Gated layer progression ✅ shipped
- [x] Tracker only looks at the first-incomplete ("active") layer; later layers are gated
- [x] Per-layer `baselineCounts` captured at the moment the layer activates — rides done earlier don't bank toward it
- [x] HUD / Screen / chat show `0/k` for future gated layers (visible but un-progressable)
- [x] Completed/active layers keep their baseline so existing progress doesn't regress
- [x] Migration: old plans without per-layer baselines seed active+earlier layers from `plan.snapshotCounts`

### Stage 4.5 — Generator tuning ✅ shipped
- [x] Retired 2-of-3 capstone (layer type still handled for backward compat when loading old plans)
- [x] `k` varies with ride duration: >10 min → 1; 5–10 min → 2 or 3; <5 min → 2–5
- [x] Enchanted Tiki Room + Red Car Trolley are companion-required — never surface as SINGLE-layer nodes, only in OR/AND branches

### Stage 4 — Branching layers + infinite chain ✅ shipped
- [x] New `DailyPlanLayer` with `LayerType` (SINGLE / OR / AND / TWO_OF_THREE)
- [x] Generator picks layer type with weighted roll (50% SINGLE, 35% OR, 12% AND, 3% 2-of-3); layer-0 is always SINGLE; every 3rd layer prefers an act-break
- [x] "No ride repeats within last 2 layers" constraint
- [x] **Infinite chain**: when unfinished layers fall below 3, `DailyPlanProgressTracker` calls `DailyPlanGenerator.appendLayers` to top up. A session never runs out.
- [x] Per-layer completion rule: ALL (AND) / ANY (OR) / 2-of-3 / single
- [x] Layer-level celebration (player-levelup, brighter text, 18 particles) on each layer flip
- [x] Chat renderer groups layer nodes under a `[OR]` / `[AND]` / `[2/3]` header
- [x] HUD renderer renders layer columns with vertical node stacks + `OR`/`AND`/`2/3` badge on top
- [x] HUD sliding window (4 layers max) with `…` prefix/suffix when there's more off-screen
- [x] Legacy plan migration: Stage 1–3 plans (`nodes` field only) auto-wrap into SINGLE layers on load

**How to test Stage 4**
1. Launch with new JAR. If you had a Stage-3 plan file, it auto-migrates to SINGLE layers. Delete it for a clean start.
2. `/rideplan` → expect a mixed-type plan. Some layers should show `[OR]`, possibly `[AND]`, and by layer 3+ an act-break gate.
3. HUD at the top shows up to 4 layers in a sliding window. Each branching layer has its badge above and nodes stacked below.
4. Complete a node. If it's in an OR layer, the whole layer flips complete on the first done node — expect layer celebration: `[IMF] Layer [OR] 2 complete!` + levelup sound + 18 particles.
5. Complete all nodes in an AND layer → that's when the layer flips.
6. After a layer completes, the tail auto-extends. You should **never see the plan "finish"** — the JSON file grows and the HUD slides to keep showing the active layer.
7. Check the JSON: `layers` array with each entry having `type`, `nodes[]`, `completed`. Size grows over the day.
8. Set `onlyAutograbbing`, delete plan, relog → generator draws from autograb-only pool.

### Stage 5 — Screen UI ✅ shipped
- [~] `DailyPlanScreen` — full-window scrollable vertical list of all layers *(superseded: the full-window screen was cut in favor of the HUD-based display; HUD covers all the same information without a separate screen)*
- [~] Header with title + date + completed/total layer count + riding countdown (if riding) — rendered in HUD
- [~] Each layer row — rendered as horizontal node chain in HUD (Stage 3.5)
- [~] Active layer highlight — HUD renders active layer inline in the sliding window
- [~] Mouse wheel scroll — HUD uses sliding window with `…` indicators instead
- [~] `/rideplan open` subcommand and `J` keybind — not registered; plan is always visible via HUD toggle
- [~] ESC / "Close" button — not applicable (no screen)
- [ ] Deferred: sprite atlas for ride icons — stays text-based for now

**How to test Stage 5**
1. Launch ImagineFun with the new JAR.
2. Run `/rideplan open` or press **J** → full-window Daily Ride Plan opens.
3. Header shows title + date + `n/m layers` + riding info (if riding).
4. Each layer appears as a row with its number, status glyph, type badge (if branching), and all nodes as side-by-side boxes.
5. Active layer has a faint white highlight bar.
6. Mouse-wheel scroll, arrow keys, or Page Up/Down to browse. Scroll hints (▲/▼) appear when there's more content off-screen.
7. Press ESC or click **Close** → returns to the previous screen.
8. Open Controls settings → search "Daily Ride Plan" → category "ImagineMoreFun" → rebind J to something else.

### Stage 6 — Passport persistence
- [ ] `passport.json` accumulates per-ride stamp counts + day history
- [ ] Populate from Stage 2 onward (backfill not attempted)
- [ ] No UI yet

### Stage 7 — Side quests + Ambassador voice
- [ ] Side-quest node bank (~15 to start)
- [ ] 2–3 side-quest branches per tree
- [ ] Tier-2 flourish on completion
- [ ] `~15` Ambassador lines, 90s rate limit

## Deferred (post Stage 7)

Passport Screen, Hidden Mickeys, titles, Fast Passes, Heat/opt-in difficulty, mood selector,
Yesterday's Echo, weekly summary in `/ridereport`, `/randomride tree` flag.
