# Space Mountain / Hyperspace Mountain — overlay optimization guidelines

Companion to [SPACE-MOUNTAIN-PROGRESS.md](SPACE-MOUNTAIN-PROGRESS.md). That doc describes **what is deployed**; this one captures the **deep design guidelines** for evolving the overlay experience.

## Source-of-truth context

- Master gate: `SpaceMountainOverride.isActive()` (connected to ImagineFun **and** current ride is `SPACE_MOUNTAIN` or `HYPERSPACE_MOUNTAIN`).
- Dome anchor: `DOME_CENTER = (-270, 80, 167)`, ~80×80 hollow, floor Y≈62, ceiling around Y≈100.
- PeopleMover hole bbox: `X:[-283,-223] Y:[74,83] Z:[215,220]`, covered with `black_concrete` (rule: `air ∪ barrier ∪ light` → `black_concrete`).
- Star renderer: 1500 billboards, 60-block sphere, additive emissive ([SpaceMountainStarRenderer.java](src/main/java/com/chenweikeng/imf/nra/spacemountain/SpaceMountainStarRenderer.java)).
- Iteration env: singleplayer world `mp.imaginefun.net` (dim `minecraft:overworld`), DebugBridge on port 9876.

### Known landmarks

| Landmark | Bbox | Notes |
|---|---|---|
| PeopleMover window (south face) | `X:[-273..-247] Y:[76..80] Z:215` + outlier `(-262, 88, 215)` | **27×5 = 135-cell** solid wall of barriers on a single Z plane, plus one stray barrier above it at Y=88. Earlier "8×5" estimate was wrong — that was an R=4 player-radius clip, not the actual extent. Full extent verified across two independent dumps. Sits fully inside the existing override bbox `X:[-283..-223] Y:[74..83] Z:[215..220]`, so coverage is fine. |

### Surrounding block at the PeopleMover window

The blocks directly above (Y≥81), below (Y≤75), and on either side of the window are all `minecraft:black_concrete`. Within R=8 of the window: **614 black_concrete, 0 wool of any color**. The "black cotton thing" the user references in the design is unambiguously `minecraft:black_concrete` — wool is not used in this part of the dome wall. The filler block for any window/hole substitution rule should match: `Blocks.BLACK_CONCRETE.defaultBlockState()`.

## Guidelines

### G-1. PeopleMover window — fill rule
**Definition (per user, 2026-05-09):** "the PeopleMover hole" = the **135-cell (27×5) barrier wall** at `X:[-273..-247] Y:[76..80] Z:215`, plus the outlier barrier at `(-262, 88, 215)`. Nothing else. The other ~1750 barriers in the dome are set dressing / prop collisions / lighting fixtures and **must not be filled** — covering them with black_concrete would hide intentional geometry.

**Spec:** while `SpaceMountainOverride.isActive()`, replace `air ∪ barrier ∪ light` with `minecraft:black_concrete` inside a tight bbox around the window only.

**Status:** existing rule [SpaceMountainBlockOverride.java:35-40](src/main/java/com/chenweikeng/imf/nra/spacemountain/SpaceMountainBlockOverride.java:35) already does this, with bbox `X:[-283..-223] Y:[74..83] Z:[215..220]`. The window falls fully inside, and direct test (`apply()` returning `black_concrete` for sample window coords on the live ride) confirmed the data layer is correct.

**Apparent bug — chunk re-mesh on activation:** while the user was riding, the south window still rendered as see-through despite `isActive()=true` and `level.getBlockState((-265, 78, 215)) == black_concrete`. Forcing `mc.levelRenderer.allChanged()` from the bridge appeared to fix it. Likely cause: the activation-edge re-mesh in [SpaceMountainBlockOverride.onTick:83-92](src/main/java/com/chenweikeng/imf/nra/spacemountain/SpaceMountainBlockOverride.java:83) ran against a `levelRenderer` that hadn't built any meshes for this ride yet, or the `previousActive` toggle missed the transition (e.g. the player joined the ride before the tick handler observed `previousActive=false`).

**Proposed hardening (pending user sign-off):**
- Trigger `allChanged()` on **every transition into active** unconditionally — i.e. on the first tick where `isActive()==true` after the player loads the world or the renderer is non-null, even if `previousActive` was already `true` from a prior ride.
- Or, narrower: re-mesh just the chunks containing the window (`SectionPos.of(-264, 78, 215)`) when `isActive()` flips on, instead of the whole world.

**Bbox-tightening note (optional):** the current bbox `[-283..-223] × [74..83] × [215..220]` is wider than the actual 27×5 window plus its Y=88 outlier (true extent: `X:[-273..-247] Y:[76..80, 88] Z:215`). It's harmless because only `air/barrier/light` cells flip and the surrounding `black_concrete` wall is left alone, but if we want to be strict, tighten to `X:[-274..-246] Y:[75..89] Z:[214..216]` (1-cell margin around the union).

### G-2. Geometry / set dressing rules
_TBD — user will provide_

### G-3. Lighting & atmosphere
_TBD — user will provide_

### G-4. Starfield & motion cues
_TBD — user will provide_

### G-5. Show props (armor stands, vehicles, signage)
_TBD — user will provide_

### G-6. Performance budget & culling
_TBD — user will provide_

### G-7. Failure modes to avoid
_TBD — user will provide_

## Decision log

| Date | Guideline | Decision | Resulting code change |
|---|---|---|---|
| 2026-05-09 | G-1 | Window filler block confirmed as `minecraft:black_concrete` (not wool) — matches existing rule. Gate scope (Space-Mountain-only vs. PeopleMover-too vs. always-when-in-dome) pending. | None yet. |
| 2026-05-09 | G-1 | "PeopleMover hole" scope clarified by user: only the window at Z=215. Other dome barriers are intentional set dressing — do NOT fill. Earlier proposal to widen the bbox dome-wide retracted. | None yet. |
| 2026-05-09 | G-1 | Window extent corrected from 8×5 (R=4-clipped scan, wrong) to **27×5** at `X:[-273..-247] Y:[76..80] Z:215`, plus an outlier barrier at `(-262, 88, 215)`. Existing override bbox still fully covers it. | None yet. |
| 2026-05-09 | G-1 | Live test on Hyperspace Mountain showed the window still rendered see-through despite `isActive()=true` and data-layer override returning `black_concrete`. Forcing `mc.levelRenderer.allChanged()` via the bridge appeared to fix it — points to a re-mesh-on-activation timing bug, not a rule bug. | None yet. Hardening proposal pending. |

## Open questions

- **G-1 gate scope:** should the PeopleMover window fill apply (a) only when riding Space Mountain (current behavior), (b) also when riding the PeopleMover, (c) always when the player is inside the dome bbox, or (d) always (unconditional)?
