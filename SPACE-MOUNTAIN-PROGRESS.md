# Space Mountain / Hyperspace Mountain client overrides

Client-side effects layered onto Hyperspace Mountain (and seasonally Space Mountain — the same physical building) on the ImagineFun server: a projected starfield, a launch-tunnel cylinder, ride audio, dome block-overlay, and show-prop hiding. **The server is never modified** — everything is client-side rendering and client-side block-state replacement.

> **Rewritten 2026-05-17, updated 2026-05-19.** This version reflects the current code. The STL subsystem, the freestanding hyperspace-streak renderer, the `/imf dumpchunks` slash-command, and the animation recorder were all **deleted** — see "Removed" at the bottom. The 2026-05-19 update finalized the disco-ball starfield (spin easing, watertight-shell mask) and the static star layer, re-baked the dome overlay, and stripped the in-mod bridge bakers (`SpaceMountainChunkDump`, `SpaceMountainBorderBake`).

---

## Master gate

`SpaceMountainOverride.isActive()` returns true iff **all** of:
- `BAKING_MODE` is `false` (a compile-time kill-switch constant, normally `false`);
- `ModConfig.currentSetting.spaceMountainEnhancements` is on — the **"Space Mountain Enhancements"** config toggle (Modifications tab), which **defaults on**;
- connected to ImagineFun (`ServerState.isImagineFunServer()`);
- `CurrentRideHolder.getCurrentRide()` is `SPACE_MOUNTAIN` or `HYPERSPACE_MOUNTAIN`.

`SpaceMountainBlockOverride`, `SpaceMountainTunnelRenderer`, `SpaceMountainEntryTunnelSeal`, `SpaceMountainRideAudio`, `SpaceMountainDiscoBall`, and `SpaceMountainStarRenderer` all route through this gate.

While the gate is active the fullbright override is also suppressed — the dome stays dark for the star effect, regardless of the configured fullbright mode (`NraLivingEntityMixin` / `DayTimeHandler`).

## Build / deploy

`./build-and-deploy.sh` from the repo root — builds, then atomically swaps `imaginemorefun-3.0.1.jar` into the PrismLauncher instance:
`~/Library/Application Support/PrismLauncher/instances/ImagineFun/.minecraft/mods/`. The atomic swap keeps a running game's open jar handle valid; never plain-`cp` the jar in.

## File layout — `src/main/java/com/chenweikeng/imf/nra/spacemountain/`

| File | Role |
|---|---|
| `SpaceMountainOverride.java` | Master gate (above). |
| `SpaceMountainDiscoBall.java` | **The primary star effect** — disco-ball light projectors. |
| `SpaceMountainBlockOverride.java` | Dome block-overlay (IFOV) — replaces dome cells while riding. |
| `SpaceMountainTunnelRenderer.java` | Launch-tunnel cylinder + tunnel screen effects. |
| `SpaceMountainEntryTunnelSeal.java` | Plugs the launch-tunnel entry mouth with black concrete once the red tunnel effect starts; restores on ride-end. |
| `SpaceMountainRideAudio.java` | Wind + rail-friction audio loops. |
| `SpaceMountainStarRenderer.java` | Static baked dome-wall starfield (~1000 stars) — a secondary layer rendered alongside the disco ball. |
| `SpaceMountainTrackRenderer.java` | Baked coaster-tube geometry (rails + spine + V-struts), drawn as near-invisible dark metal. |
| `SpaceMountainEntityHider.java` | Whitelist of show-prop armor stands to hide; queried by `NraEntityRendererHideMixin`. |
| `ImfRenderPipelines.java` | Custom render pipelines (`OPAQUE_SCREEN`, `ENTITY_THROUGH_WALLS`). |

Registered in `ImfClient.onInitializeClient()` in this order: `SpaceMountainStarRenderer`, `SpaceMountainTrackRenderer`, `SpaceMountainBlockOverride.init()`, `SpaceMountainTunnelRenderer`, `SpaceMountainEntryTunnelSeal`, `SpaceMountainDiscoBall`, `SpaceMountainRideAudio`.

---

## Star effect — `SpaceMountainDiscoBall`

The primary starfield. Emulates disco-ball light projectors: a set of fixed "balls" each emit beams that raycast outward; wherever a beam hits a surface, a star dot is drawn. Spinning a ball drags its dots across the surfaces.

**Model.** Each `Ball` has a position `(x,y,z)`, an aim `(yaw, pitch)`, a `spinDeg`/`spinRate`, and a close-in cap (`closeRadius`, `maxCloseDots`). `BEAMS_PER_BALL = 800` beams are spread over a 240° cone (`CONE_HALF_ANGLE_DEG = 120`) as a fibonacci spiral, shared across all balls in a local frame and rotated to each ball's aim + spin.

**Projection (`project` / `castBeam`).** Each beam is raycast up to `MAX_BEAM = 128` blocks via `Level.clip(ClipContext)` — **world blocks only**. (There used to be an STL-mesh raycast too; the STL subsystem was deleted — beams now hit only real Minecraft blocks.) The nearest hit point becomes a star dot. A beam whose hit *block* is not part of the watertight dome shell — the `dome_borders.bin` face set loaded by `loadBorders()` into `watertightWallCells` — is dropped, so stars never land in entrance/exit tunnels or outside the dome. A beam whose hit *cell* is in the prismarine-cover exclusion set is also dropped. A per-ball close-in cap drops the closest dots within `closeRadius` so stars don't clump on the ball itself.

**Spin / auto-spin.** `setSpin(index, degPerSec)` spins one ball; auto-spin (`AUTO_SPIN_INTERVAL_SEC = 20`) hands the spin to a random ball every 20 s, never the same one twice. Spin eases in and out — a ball's angular velocity ramps toward its target rate at `SPIN_ACCEL_DEG_PER_SEC2`, so it accelerates from rest and decelerates to a stop rather than snapping. A spinning ball is re-projected each frame, so its dots sweep.

**Rendering.** `render()` is a `WorldRenderEvents.AFTER_ENTITIES` callback. It guards in two steps: first `if (!ENABLED || balls.isEmpty()) return;`, then the master-gate check `if (!SpaceMountainOverride.isActive() && !(mc.hasSingleplayerServer() && spDevPreview)) return;`. It then draws every ball's cached dots as camera-facing quads with `RenderTypes.eyes` (emissive/full-bright, so stars stay visible in the dark dome). Like every other Space Mountain overlay, the disco ball routes through the `SpaceMountainOverride.isActive()` master gate — the stars render only while actually riding Space/Hyperspace Mountain on ImagineFun with the Space Mountain Enhancements toggle on, never on other servers. The one exception is `spDevPreview`, a bridge-only, non-persisted dev flag (`setSpDevPreview`) that lets the effect render in single-player for tuning against the SP simulator world. `ENABLED` is a secondary debug kill (defaults `true`); it can force the effect off but cannot bypass the gate.

**Persistence — config-dir file, JAR-bundled fallback.** `load()` / `loadExclusion()` go through `readConfigOrBundled()`: read `config/imaginemorefun/<file>` if it exists, else fall back to the copy bundled in the jar (`src/main/resources/imaginemorefun/`). The bundled copy ships the default starfield with the mod, so a fresh install — or a launcher migration that drops the config dir — still has working stars. Runtime-API writes (`addBall`, `setSpin`, …) always land in the config-dir file, which then takes precedence on the next load.
- `disco_balls.json` — the balls (position/aim/spin/close-cap) plus `autoSpinEnabled`/`autoSpinRate`. Written on every change, read once in `register()` via `load()`.
- `disco_exclusion.json` — `{"cells":[x,y,z,x,y,z,…]}`, the prismarine "cover" cells that suppress stars. Read in `register()` via `loadExclusion()`. Baked by `bake-disco-exclusion.py` from a chunk capture (the bake scripts and the chunk-dump tooling live outside the repo — see "Removed").

**Runtime API (over the DebugBridge / `mc_execute`):** `addBall(x,y,z,yaw,pitch)`, `clearBalls()`, `setSpin(index,deg)`, `setAutoSpin(enabled,deg)`, `setCloseLimit(index,closeRadius,maxDots)`, `reproject()`, `setEnabled(bool)`, `setSpDevPreview(bool)`, `describe()`. `describe()` is the quickest health check — it reports `balls=N`, `enabled`, and per-ball dot counts.

---

## Static star layer — `SpaceMountainStarRenderer`

A secondary, non-moving star layer rendered alongside the disco ball: roughly `STAR_COUNT = 1000` dots picked once from the baked dome-wall faces in `dome_borders.bin`, so they sit flush on the dome shell and never move. `render()` routes through the same `SpaceMountainOverride.isActive()` master gate, with the same `spDevPreview` single-player dev bypass. `dome_borders.bin` is read config-dir-first (an offline-baked result dropped into the config dir) and falls back to the bundled resource; `reload()` re-reads it and re-picks the stars. Bridge API: `setEnabled(bool)`, `setSpDevPreview(bool)`, `reload()`.

---

## `SpaceMountainBlockOverride` — dome block overlay (IFOV)

Replaces dome block states while the ride gate is active. Reads a **single** binary, `dome_overlay.bin` (magic `IFOV`, version 1) — an offline-baked diff between the live ImagineFun world and a curated SP simulator world. This replaced an older four-layer runtime model (PeopleMover bbox seal, explicit cover cells, animation-suppress, knockout patch); those are all now collapsed into the one overlay.

- `loadOverlay()` parses the binary into `rawOverlayEntries` at class-load. Once `mc.level` is available, entries are parsed (via `BlockStateParser`) and indexed into `overlayByChunk` (keyed by chunk long-key) for O(1) per-chunk lookup.
- `sealChunk(chunk)` / `sealCellIfNeeded(pos)` apply the overlay; `originalStates` records each replaced cell's pre-seal `BlockState`.
- When the gate flips off, originals are restored (the client sees a "server update" reverting the geometry).
- Packet hooks live in `NraClientPacketListenerChunkMixin` (`@At("TAIL")` on `ClientPacketListener.handleLevelChunkWithLight` / `handleBlockUpdate` / `handleChunkBlocksUpdate`). Mutating the chunk's stored block states (not per-cell `getBlockState`) is what survives Sodium.
- `init()` watches the active flag each tick and forces a full re-mesh on transition.

To change what the dome looks like: edit the SP simulator world, re-capture the live + SP chunk dumps, and re-run `~/imf-debug-dumps-archive/bake-overlay.py`. The in-mod bridge dumper was removed in the 2026-05-19 cleanup — the dump + bake workflow now lives entirely in `~/imf-debug-dumps-archive/` (see "Removed").

## `SpaceMountainTunnelRenderer`

Renders a cylindrical "launch-tunnel cover" around the rider during the tunnel window, plus screen effects on the cylinder wall (rotating starfield, purple double-rings, a red radial-stripe phase). The cylinder is a tilted axis from `START` to `END` that follows the rider's climbing path. The hyperspace warp-streak path (`HYPERSPACE_EFFECT_ENABLED`) is currently `false`. Axial/lateral position gates are commented out pending cylinder re-calibration.

## `SpaceMountainRideAudio`

Two persistent looping sounds — wind and rail-friction — whose volume/pitch track the vehicle's per-tick speed and yaw rate. Started/stopped on the `SpaceMountainOverride.isActive()` transition. Coupled to the OpenAudioMc volume slider.

## `SpaceMountainEntityHider`

A static whitelist of `(itemId, damage)` helmet signatures (e.g. the TIE Fighter / X-Wing shoulder-pet props, both `minecraft:diamond_sword` with custom-model damage). `NraEntityRendererHideMixin` queries `shouldHide(stand)` and skips rendering matching armor stands while riding.

---

## Pre-baked resources — `src/main/resources/imaginemorefun/`

| Resource | Consumed by | Notes |
|---|---|---|
| `dome_overlay.bin` | `SpaceMountainBlockOverride` | IFOV v1 — the dome block diff. |
| `dome_borders.bin` | `SpaceMountainStarRenderer`, `SpaceMountainDiscoBall` | IFDB v1 baked dome-wall faces. The star renderer places its static stars on them; the disco ball uses them as the `watertightWallCells` projection mask. Pre-baked offline — the bake tool was removed (see "Removed"). |
| `dome_track.bin` | `SpaceMountainTrackRenderer` | Recorded vehicle path — the rail/spine geometry is baked from this. |
| `dome_track_stars.bin` | `SpaceMountainStarRenderer` | Track-surface stars — `INCLUDE_TRACK_STARS=false`. |
| `disco_balls.json` | `SpaceMountainDiscoBall` | Bundled default projectors — `load()` fallback when no config-dir file. |
| `disco_exclusion.json` | `SpaceMountainDiscoBall` | Bundled default prismarine-cover cells — `loadExclusion()` fallback. |
| `textures/particle/star.png` | `SpaceMountainDiscoBall`, `SpaceMountainStarRenderer` | Star dot texture. |
| `textures/particle/track.png`, `hyperspace_streak.png` | track / tunnel renderers | — |

## Removed

Do not look for these — they were deleted:
- **STL subsystem** — `SpaceMountainStlOverlay`, `SpaceMountainStlBvh`, the `space_mountain.stl` + `stl_stars.bin` resources. The disco ball previously raycast an STL mesh; it now raycasts world blocks only.
- `SpaceMountainHyperspaceRenderer` — freestanding warp-streak renderer, superseded by the tunnel renderer.
- `SpaceMountainAnimationRecorder` and the `/imf dumpchunks` slash-command (`ChunkDumpCommand`) — debug/recording tooling.
- `SpaceMountainChunkDump` and `SpaceMountainBorderBake` (2026-05-19) — bridge-callable dev tools: the chunk dumper feeding the offline overlay bake, and the watertight flood-fill that baked `dome_borders.bin`. Stripped in the pre-release cleanup; the overlay/border bake workflow now lives entirely in `~/imf-debug-dumps-archive/`.
- The `debug-dumps/` directory (Python bake scripts + captures) was moved out of the repo to `~/imf-debug-dumps-archive/`.

## Resuming cold

1. Read this file.
2. `git log --oneline -20` for recent commits.
3. DebugBridge port **9876** — connect via the `mcdev-mcp` MCP to inspect live state (`SpaceMountainDiscoBall.describe()` is the fastest star-effect health check).
4. Build/deploy: `./build-and-deploy.sh` (never plain `cp`).
