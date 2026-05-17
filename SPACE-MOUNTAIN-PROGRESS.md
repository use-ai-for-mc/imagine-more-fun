# Space Mountain / Hyperspace Mountain client overrides

Client-side modifications that turn Hyperspace Mountain (and seasonally Space Mountain — same physical building) into a "ride through space" with replaced launch-tunnel, starfield, custom coaster geometry, hyperspace warp effect, and selectable show-prop tweaks. **Server is never modified**; everything happens on the client.

Master gate: `SpaceMountainOverride.isActive()` → true iff connected to ImagineFun **and** `CurrentRideHolder.getCurrentRide() ∈ {SPACE_MOUNTAIN, HYPERSPACE_MOUNTAIN}`. Off-ride traffic costs one boolean check; every override fast-paths back to vanilla.

Build/deploy: `./build-and-deploy.sh` from repo root (atomic-swap, preserves a running JVM's jar handle). Default jar: `imaginemorefun-3.0.1.jar` → `~/Library/Application Support/ModrinthApp/profiles/ImagineFun/mods/`.

DebugBridge port: **9876**. `mcdev-mcp` MCP server exposes `mc_execute` (Lua reflection), `mc_screenshot`, `mc_nearby_entities`, etc. The mod's own `/imf dumpchunks <radius>` command writes a gzipped binary per call to `debug-dumps/chunks-<timestamp>.bin.gz` (pure-Java iteration, no Lua bridge cost).

---

## Architecture overview

Three independent overlays plus a chunk-mutation pipeline:

```
              [SpaceMountainStarRenderer]      [SpaceMountainHyperspaceRenderer]
                3000 dome stars +                600 launch-tunnel streaks
                500 track-surface stars          (elapsed 40-55 s)
                          \                         /
                           \                       /
                            \   [SpaceMountainTrackRenderer]
                             \   pre-baked coaster geometry
                              \  rails + spine + V-bracing struts
                               \  (RENDER_START_SAMPLE onward)
                                \                |
                                 \_______________|________ on every frame while active
                                                 |
                              [SpaceMountainBlockOverride]
                              chunk-mutation pipeline:
                              • PeopleMover bbox seal
                              • EXTRA_COVER_POSITIONS (time-gated)
                              • Knockout patch (IFKN binary)
                              • Animation suppress (IFAS binary)
                              • DeSeal on gate-flip-false
```

### File layout

| Path | Role |
|---|---|
| `src/main/java/com/chenweikeng/imf/nra/spacemountain/SpaceMountainOverride.java` | Master gate |
| `…/SpaceMountainBlockOverride.java` | Chunk-mutation pipeline (seal/deseal) |
| `…/SpaceMountainStarRenderer.java` | Dome wall + track surface stars |
| `…/SpaceMountainTrackRenderer.java` | Coaster geometry (rails + spine + struts) |
| `…/SpaceMountainHyperspaceRenderer.java` | Launch-tunnel warp streaks |
| `…/SpaceMountainEntityLightOverride.java` | Whitelist of armor stands to FULL_BRIGHT |
| `…/SpaceMountainAnimationRecorder.java` | Diagnostic — records server packet events 40-55 s |
| `…/ImfRenderPipelines.java` | Custom Sodium-compatible no-depth render pipeline |
| `…/ChunkDumpCommand.java` | `/imf dumpchunks` implementation |
| `src/main/java/com/chenweikeng/imf/mixin/Nra*` | Mixin classes registered in `imf.mixins.json` |
| `src/main/resources/imaginemorefun/` | Pre-baked binary resources |
| `src/main/resources/assets/imaginemorefun/textures/particle/` | Star + streak + track textures |
| `debug-dumps/` | Python tooling + captured dumps |

### Pre-baked resources

| Resource | Source | Size | Purpose |
|---|---|---|---|
| `imaginemorefun/dome_track.bin` | `bake-track.py track-*.csv` | ~38 KB | Vehicle path samples (~1200 samples at 6.67 Hz) — input to track + track-stars renderers |
| `imaginemorefun/dome_borders.bin` | `bake-borders.py chunks-*.bin.gz` (with watertight tunnel-mouth seal) | ~365 KB | 28 011 dome-wall faces — input to star renderer |
| `imaginemorefun/dome_track_stars.bin` | `bake-track-stars.py` | ~12 KB | 500 random surface positions on coaster track |
| `imaginemorefun/dome_knockout.bin` | `bake-knockout-multi.py --remove-only` | ~150 KB | Per-cell `→ air` removals (currently 5643 entries) |
| `imaginemorefun/dome_animation_suppress.bin` | `bake-animation-suppress.py` | ~10 KB | 873 cells pinned to `black_concrete` to kill server warp flicker |
| `assets/imaginemorefun/textures/particle/star.png` | static | ~1 KB | Dome-wall star texture |
| `assets/imaginemorefun/textures/particle/track.png` | PIL-generated white | ~1 KB | Track tube texture |
| `assets/imaginemorefun/textures/particle/hyperspace_streak.png` | PIL gradient | ~2 KB | Streak texture (transparent ends, blue-white core) |

---

## SpaceMountainBlockOverride — chunk mutation pipeline

Single source of truth for "what should the chunk look like during a ride". Four layered rules, all funnelled through one seal/deseal infrastructure.

### Apply order in `sealChunk(chunk)` (current state after the May-11 ordering fix)

1. **PeopleMover bbox** (`HOLE_X_MIN/MAX × HOLE_Y_MIN/MAX × HOLE_Z_MIN/MAX = X:[-283,-223] Y:[74,83] Z:[215,220]`) — replaces `air ∪ barrier ∪ light` with `minecraft:black_concrete`.
2. **`EXTRA_COVER_POSITIONS`** (42-cell launch-tunnel mouth at Z=201) — time-gated on `elapsedSeconds ≥ EXPLICIT_SEAL_AFTER_SECONDS` (70 s). Pins each cell to `black_concrete` unconditionally.
3. **Animation suppress** (`dome_animation_suppress.bin`, 873 cells) — pins to `black_concrete` regardless of current state. Kills the server's `light_blue_wool` / `concrete_powder` warp flicker.
4. **Knockout patch** (`dome_knockout.bin`, currently 5643 `→air` entries) — **runs LAST so user demolitions beat anim-suppress**. Critical ordering invariant — the original order had anim-suppress last, which clobbered demolitions in the launch tunnel.

### State tracking

- `originalStates: Map<BlockPos, BlockState>` — every cell we mutate records its pre-seal value.
- `desealAll(mc)` on gate-flip-false restores each entry, then clears the map. The client sees a "server update" reverting to the live geometry.
- DISCONNECT handler clears `previousActive`, `pendingRemesh`, `originalStates` so a rejoin re-runs from scratch.

### Packet hooks (`NraClientPacketListenerChunkMixin`)

Hook three packet handlers at `@At("TAIL")` on `ClientPacketListener`:
- `handleLevelChunkWithLight` → `sealChunk(chunk)` for every freshly-installed chunk.
- `handleBlockUpdate` → `sealCellIfNeeded(pos)` for incremental block changes.
- `handleChunkBlocksUpdate` → `sealCellIfNeeded(pos)` per cell in the section-blocks update.

This is the key insight that survives Sodium: mutating chunk-stored block states (via `chunk.setBlockState(pos, target, 0)` + `levelRenderer.setSectionDirty(...)`) propagates through every renderer (vanilla, Sodium, Iris). Per-cell `getBlockState` mixins were tried and dropped — Sodium reads section palettes directly.

### Activation/eager-seal behavior

- On gate-flip false → true: `pendingRemesh = true`. Next tick (after `levelRenderer != null && mc.level != null`), `sealAllLoadedChunks(mc)` walks every chunk in `sealRegionChunkKeys()` and seals whatever is currently loaded. Chunks still streaming in get sealed at packet-arrival time. **No `areHoleChunksLoaded` wait** — that caused a 10-20 s delay through the dome interior.
- On gate-flip true → false: `desealAll(mc)` restores from `originalStates`.

---

## Star renderer

`SpaceMountainStarRenderer.loadAndPickStars()`:
1. Load `dome_borders.bin` (28 011 face entries: `block_pos + face_dir_idx`).
2. Pre-compute world positions: `block_center + 0.5 × face_normal`.
3. Partial Fisher-Yates with `SEED = 0xCAFEBABE` to pick `STAR_COUNT = 3000` faces. Same dome → same 3000 stars per session.
4. Append all 500 track-surface stars from `dome_track_stars.bin` (smaller billboard size `× 0.6`).

Renders on `WorldRenderEvents.AFTER_ENTITIES` via `BufferSource.getBuffer(RenderTypes.eyes(STAR_TEXTURE))` — emissive, depth-test on, depth-write off.

Bake notes:
- `bake-borders.py` does a flood-fill from the player position through `minecraft:air` only. Tunnel boundaries (blue_wool markers, barriers, light) block the flood, keeping it watertight inside the dome.
- Safety guards: `DOME_BBOX = X[-330..-200] Y[50..120] Z[100..230]` and `MAX_VISITED = 250_000`. Without these a leaky dome (e.g. after demolition) would flood the entire world and OOM (we hit ~17 GB RAM once).
- The current `dome_borders.bin` was baked from `chunks-1778344197256.bin.gz` — pre-demolition, naturally watertight.

---

## Track renderer

`SpaceMountainTrackRenderer.loadAndBuild()` reads `dome_track.bin` (the recorded vehicle path). Builds three continuous tube rails (left, right, spine) + V-bracing struts.

Knobs at the top of the file (current values):

```
VEHICLE_Y_OFFSET    =  0.1
RAIL_HALF_SEPARATION = 0.7   // rails 1.4 blocks apart
SPINE_DROP           = 0.6   // spine below rail centerline
TUBE_RADIUS          = 0.11
SPINE_RADIUS         = 0.15
STRUT_RADIUS         = 0.06
CROSSTIE_STRIDE      = 6
RENDER_START_SAMPLE  = 400   // skip first ~60 s (launch tunnel)
COLOR_R/G/B          = 0.08/0.08/0.09   // ~10% brightness silver
COLOR_A              = 0.45            // translucent
TRACK_BLOCK_LIGHT    = 4    // lightmap, 0..15
```

Render type: `RenderTypes.entityTranslucent(TRACK_TEXTURE)` — non-emissive, lightmap-respecting, depth-tested. Track reads as dim metal that gets occluded by walls naturally. Color × lightmap × translucency together makes it barely-visible against the dark dome — the "real coaster track" aesthetic.

History of failed earlier attempts:
- Emissive `eyes()` made the track glow neon yellow (later switched to white texture + tint).
- `NO_DEPTH_TEST` variant (`ImfRenderPipelines.entityThroughWalls`) was added then dropped — through-walls felt off; user requested standard depth.

`bake-track-stars.py` mirrors the geometry constants — must be kept in sync when any track-renderer knob changes.

---

## Hyperspace renderer

`SpaceMountainHyperspaceRenderer` — 600 long thin emissive ribbons inside the launch-tunnel volume, active during `elapsed ∈ [40, 55]` s of the ride.

```
TUNNEL_X_MIN/MAX  =  -297, -253
TUNNEL_Y_MIN/MAX  =  63, 88
TUNNEL_Z_MIN/MAX  =  126, 200
STREAK_COUNT      = 600
STREAK_LENGTH_MIN = 1.5   // blocks
STREAK_LENGTH_MAX = 4.5
STREAK_WIDTH      = 0.05
START_SECONDS     = 40
END_SECONDS       = 55
SEED              = 0xD15CE5L
```

Each streak: axis-aligned billboard, long axis fixed to world +Z (tunnel direction), short axis rotates per-frame to face the camera. **Static in world space — relies on rider motion through the field**. Future upgrade if needed: per-frame Z advance with wrap-around for a stars-streaming-past look even when vehicle is slow.

The 873-cell `dome_animation_suppress.bin` is paired with this — it pins the server's animated wool/concrete-powder cells to black_concrete so they don't fight the streak overlay.

---

## Entity light + visibility tweaks

`NraEntityRendererLightMixin` — targets `EntityRenderer.extractRenderState(T, S, float)` at `@At("TAIL")` (the 1.21 `EntityRenderState` pipeline; the older `EntityRenderDispatcher.getPackedLightCoords` was dead for non-hand entities). Sets `state.lightCoords = LightTexture.FULL_BRIGHT` for any `ArmorStand` whose head matches `SpaceMountainEntityLightOverride.shouldFullBright(stand)`.

Whitelist (extend by adding `(itemId, damage)` pairs):
- `minecraft:diamond_sword` damage 145 (TIE Fighter Shoulder Pet)
- `minecraft:diamond_sword` damage 143 (X-Wing Shoulder Pet)

`NraEntityRenderDispatcherShouldRenderMixin` — `@Inject` HEAD-cancellable on `shouldRender(...)`. Returns `false` for any `ItemFrame` whose displayed item is `minecraft:nether_star` while the gate is active. Hides the floating nether-star frames the server uses for launch-tunnel lighting.

---

## Bake / deploy workflow

Python scripts live in `debug-dumps/`. Each is invoked from that directory.

### Capture a dump

In-game: `/imf dumpchunks 8` (8 is the chunk radius; default produces ~289 chunks). Output: `debug-dumps/chunks-<timestamp>.bin.gz`.

### Bake recipes (current)

```bash
cd debug-dumps

# Track from a CSV recording (gate-active rider capture)
python3 bake-track.py track-hyperspace-*.csv
# → ../src/main/resources/imaginemorefun/dome_track.bin

# Track-surface stars (must match SpaceMountainTrackRenderer knobs)
python3 bake-track-stars.py
# → ../src/main/resources/imaginemorefun/dome_track_stars.bin

# Dome border star projection (uses a known-watertight pre-demolition dump)
python3 bake-borders.py chunks-1778344197256.bin.gz
# → ../src/main/resources/imaginemorefun/dome_borders.bin

# Animation suppression positions (873 cells)
python3 bake-animation-suppress.py animation-hyperspace-*.csv
# → ../src/main/resources/imaginemorefun/dome_animation_suppress.bin

# Knockout patch — remove-only, multi-baseline
python3 bake-knockout-multi.py --remove-only \
  after-tunnel-demolish.bin.gz \
  baseline-knockout.bin.gz \
  onride-current.bin.gz
# → ../src/main/resources/imaginemorefun/dome_knockout.bin
```

Then `./build-and-deploy.sh` from repo root.

### Diagnostic / verification scripts

- `parse_dump.py` — chunk dump parser library.
- `diff-dumps.py <baseline.bin.gz> <after.bin.gz>` — counts added / removed / swapped cells between two dumps. Restricts to common chunks to avoid boundary-noise.
- `parse-track.py track-*.csv` — quick summary of a ride recording.
- `watertight-test.py chunks-*.bin.gz` — flood-fills from player position, reports leaks past a generous dome bbox. Used to verify dome is closed before baking borders.
- `track-viewer.html` — 3D track + optional STL overlay viewer (Three.js r137 UMD, no build step). Serve with `python3 -m http.server 8000` in `debug-dumps/`.

---

## Pipeline pain points + planned unification

The current architecture has accumulated **four overlapping seal/patch layers** with subtle ordering and exclusion rules:

| Layer | Source | Target | Condition |
|---|---|---|---|
| PeopleMover bbox | hardcoded constants | `→ black_concrete` | inside bbox AND was visually empty |
| `EXTRA_COVER_POSITIONS` | hardcoded 42 cells | `→ black_concrete` | `elapsedSeconds ≥ 70` |
| Animation suppress | `dome_animation_suppress.bin` | `→ black_concrete` | always while gate active |
| Knockout patch | `dome_knockout.bin` | `→ air` (remove-only) | always while gate active |

**Bugs caused by layering**:
1. (fixed) Anim-suppress ran AFTER knockout → demolished cells got pinned back to black_concrete. Re-ordered: anim-suppress → knockout.
2. (fixed) Patch with `target=barrier` would defeat the PeopleMover bbox seal. Fix: `SKIP_TARGET_BASE = {barrier, light}` in the baker.
3. (fixed) Multi-source bake captured swap+add entries that brought "deleted" blocks back. Fix: `--remove-only` mode emits only `target=air` entries.
4. Bake script needs **two baselines** (`baseline-knockout.bin.gz` AND `onride-current.bin.gz`) to capture cells already demolished pre-baseline.

### Proposed unified architecture (next session)

Replace the four-layer pipeline with a single normalised patch format that is both:
- **Authoritative**: emitted by one bake step that takes (live-server-pristine, desired-state) and produces one binary.
- **Self-describing**: each entry carries `(pos, target_state, condition_flag)` where `condition_flag` is a tiny enum:
  - `ALWAYS` — apply whenever the gate is active.
  - `AFTER_SECONDS(N)` — apply once `elapsedSeconds >= N`.
  - (extensible — `IN_TUNNEL`, `IN_DOME`, etc. if we need them later)

Then `SpaceMountainBlockOverride` collapses to one loader and one `sealChunk` loop that iterates entries by chunk-index and applies them in entry order. PeopleMover bbox cells become explicit entries (one per cell inside the bbox where the live server has `air|barrier|light`). The 42 tunnel-mouth cells become `AFTER_SECONDS(70)` entries. Anim-suppress and knockout merge into the same stream. Deseal logic unchanged — still keyed by `originalStates`.

**Workflow for the user's next edit pass**:
1. Take a pristine **live-server** dump (off-ride, gate inactive).
2. Make demolitions / swaps in the freshly-downloaded SP world.
3. Dump the SP world.
4. Run a single unified `bake-overlay.py` that takes the live dump + SP dump + any time-gated cell list and emits one binary.
5. Build/deploy.

The bake script should also be **idempotent**: re-running with identical inputs produces the same binary (already true via deterministic seed + sorted entries — preserve this).

### Open work items for the next session

- [ ] Implement the unified patch format (`IFOV` v1 — Imf OVerlay).
- [ ] Migrate `dome_knockout.bin` + `dome_animation_suppress.bin` + the bbox + tunnel-mouth seal into a single `dome_overlay.bin`.
- [ ] Replace the four code paths in `SpaceMountainBlockOverride` with one entry-driven loop.
- [ ] Update the bake scripts (collapse `bake-knockout-multi.py` + `bake-animation-suppress.py` + the implicit bbox/tunnel-mouth constants into one `bake-overlay.py`).
- [ ] Mesh-staleness bug (residual "deleted blocks reappearing"): need to investigate whether Sodium's section-dirty signal sometimes isn't enough — possibly add a tiny periodic re-mesh of the seal region during the ride.

---

## Resuming in a new session

When you pick this up cold:
1. Read this file top-to-bottom (≈300 lines).
2. Check `git log --oneline -20` for recent commits.
3. Look at `debug-dumps/` for the latest captured dumps (file timestamps).
4. The DebugBridge port is **9876** — connect via `mcdev-mcp` MCP to inspect live state.
5. Build/deploy: `./build-and-deploy.sh` from repo root (never plain `cp` — atomic-swap preserves the running JVM).
6. To regenerate stars/track/patches after dome changes: see the bake recipes section above.
