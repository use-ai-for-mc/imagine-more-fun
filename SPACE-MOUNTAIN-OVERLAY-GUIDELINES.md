# Space Mountain / Hyperspace Mountain — overlay design guidelines

Companion to [SPACE-MOUNTAIN-PROGRESS.md](SPACE-MOUNTAIN-PROGRESS.md). PROGRESS.md describes **what is deployed and how each subsystem works**; this doc is the **design-decision log** — the rationale and rules behind the overlay experience, plus open questions awaiting a call.

> **Rewritten 2026-05-17.** The previous version predated the IFOV-overlay unification and the STL / debug-tooling cleanup. Its context section and its one filled-in guideline (G-1) described a runtime block-fill rule that no longer exists. This version is re-grounded on the current architecture. G-2…G-7 were never written and remain open.

## Source-of-truth context

For the authoritative description of each subsystem, see PROGRESS.md. In brief, current as of 2026-05-17:

- **Master gate — `SpaceMountainOverride.isActive()`.** True only when `BAKING_MODE` is off, the "Space Mountain (Beta Preview)" config toggle is on, the client is connected to ImagineFun, and the current ride is `SPACE_MOUNTAIN` or `HYPERSPACE_MOUNTAIN`. Every overlay — block overlay, launch tunnel, ride audio, and the disco-ball starfield — routes through it. Single-player and other servers fail the ImagineFun check, so nothing activates there.
- **Dome block overlay — `SpaceMountainBlockOverride`.** Applies `dome_overlay.bin` (magic `IFOV`, v1), an **offline-baked block-state diff** between the live ImagineFun world and a curated single-player simulator world. There is no runtime fill rule: the older four-layer model (PeopleMover bbox seal + explicit cover cells + animation-suppress + knockout patch) was collapsed into this one diff.
- **Starfield — `SpaceMountainDiscoBall`.** Disco-ball light projectors: each "ball" raycasts beams at world blocks and draws a star dot at every hit. The default projectors and the prismarine-cover exclusion set ship in the jar (`disco_balls.json`, `disco_exclusion.json`); copies under `config/imaginemorefun/` override them.
- **Launch tunnel — `SpaceMountainTunnelRenderer`.** A cylindrical cover plus screen effects along the rider's climb path.
- **Show props — `SpaceMountainEntityHider`.** A whitelist of armor-stand prop signatures hidden while riding.
- **Iteration environment.** DebugBridge on port 9876; dome geometry is authored in a single-player simulator copy of the dome and baked from there.

## Guidelines

### G-1. Dome geometry corrections — baked, not rule-based

**Superseded.** The earlier G-1 specified a runtime fill rule for the PeopleMover window — replace `air ∪ barrier ∪ light` with `black_concrete` inside a bbox at `Z=215` while `isActive()`. That approach is retired. Every dome geometry correction — the PeopleMover window, the prismarine cover, and all other adjusted cells — is now part of the single offline-baked `dome_overlay.bin` diff that `SpaceMountainBlockOverride` applies.

**To change the dome:** edit the single-player simulator world until it shows the geometry you want, re-dump both the live and simulator worlds, and re-bake `dome_overlay.bin` (the bake scripts live in `~/imf-debug-dumps-archive/`, moved out of the repo in the 2026-05-17 cleanup). Nothing about the dome is configured in Java or by runtime rules — the diff is data.

**Historical note.** The PeopleMover window was surveyed as a 27×5 barrier wall at `X:[-273..-247] Y:[76..80] Z:215`, plus an outlier barrier at `(-262, 88, 215)`, with `minecraft:black_concrete` as the surrounding wall material. That measurement is preserved in the decision log below; it is no longer how the correction is delivered.

### G-2 … G-7 — not yet written

The original outline reserved sections for geometry & set dressing (G-2), lighting & atmosphere (G-3), starfield & motion cues (G-4), show props (G-5), performance budget & culling (G-6), and failure modes to avoid (G-7). None were ever filled in. They remain open for the design owner to write.

## Decision log

> The 2026-05-09 entries predate the IFOV-overlay unification. They describe the retired runtime fill-rule approach to the PeopleMover window and are kept for history only — the current delivery mechanism is the baked `dome_overlay.bin` (see G-1).

| Date | Topic | Decision |
|---|---|---|
| 2026-05-09 | PeopleMover window | Filler block confirmed as `minecraft:black_concrete` (not wool) — the surrounding dome wall is black_concrete. |
| 2026-05-09 | PeopleMover window | Scope clarified: "the hole" is only the window at `Z=215`. The other ~1750 dome barriers are intentional set dressing / collision / lighting and must not be filled. |
| 2026-05-09 | PeopleMover window | Extent corrected from an 8×5 estimate (an R=4 player-radius clip) to the true **27×5** wall at `X:[-273..-247] Y:[76..80] Z:215`, plus an outlier at `(-262, 88, 215)`. |
| 2026-05-09 | PeopleMover window | Live test showed the window still see-through despite the data-layer override returning `black_concrete`; forcing `levelRenderer.allChanged()` fixed it — a re-mesh-on-activation timing issue, not a rule bug. The current `SpaceMountainBlockOverride.init()` watches the active flag each tick and forces a full re-mesh on transition. |
| 2026-05-17 | Master gate | All overlays — including the disco-ball starfield, previously ungated — now route through `SpaceMountainOverride.isActive()`. Resolves the G-1 gate-scope open question (see below). |

## Open questions

- **G-1 gate scope** *(resolved 2026-05-17)* — the earlier question was whether the dome corrections should apply only while riding Space Mountain, also on the PeopleMover, whenever inside the dome bbox, or always. Answer: all overlays route through `SpaceMountainOverride.isActive()`, which scopes them to actively riding Space/Hyperspace Mountain on ImagineFun. There is no PeopleMover-ride or in-dome variant.
