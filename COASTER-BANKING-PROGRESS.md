# Coaster camera-banking — multi-ride generalization (abandoned)

Active 2026-05-20 – 2026-05-21. Tried to generalize the Space Mountain camera-bank pattern (track recording → per-sample roll → camera roll mixin) to several ImagineFun coasters. **Abandoned 2026-05-21** after discovering that the bundled SmoothCoasters mod (`SmoothCoasters-1.21.11-v1.jar`) already tilts the camera server-side on the rides we were targeting, so our work was duplicating an existing feature and producing a visible double-tilt.

## Final state

| Coaster | Status |
|---|---|
| Space Mountain / Hyperspace Mountain | camera bank **reimplemented 2026-05-26** to scale SmoothCoasters' own tilt (intercept `SmoothCoasters.setRotation`, scale the pose's roll) instead of the baked-track bank, then **generalized** into a global "Coaster Tilt" multiplier that applies to *all* SC-tilted coasters — see `SPACE-MOUNTAIN-PROGRESS.md`. The baked `dome_track.bin` now feeds only the SM/HSM rail geometry. |
| Big Thunder Mountain | dropped — SC tilts it already |
| Radiator Springs Racers | dropped — SC tilts it (we initially shipped a bake here and it appeared to work; user later confirmed SC also handles RSR and removed our duplicate) |
| Chip 'n' Dale's Gadget Coaster | dropped — SC tilts it already |
| Matterhorn Bobsleds | never baked — dropped before recording on the SC-duplication assumption |
| Incredicoaster | never baked — dropped before recording on the SC-duplication assumption |

## What survived

Code:
- `CoasterTrackData` — per-ride IFTC v1/v2 binary loader; RESOURCES map currently has only `dome_track.bin` (SM/HSM share it). Still used by the track renderer for rail-geometry banking.

> **Camera bank superseded 2026-05-26.** `CoasterCameraBank` (per-tick baked-track roll lookup, EMA-smoothed) and `NraCameraRollMixin` (roll applied at `GameRenderer.renderLevel` HEAD) were **removed** and replaced by `CoasterTiltAmplifier` + `NraSmoothCoastersRotationMixin`, which scale SmoothCoasters' own camera pose instead of computing a roll from the baked track. It was then **generalized the same day** from the SM/HSM-only "Additional Tilt" toggle into a global "Coaster Tilt" multiplier (`coasterTiltMultiplier`, double 0.0–2.0, default 1.0) that applies to every SC-tilted coaster, ImagineFun-gated. The baked `roll` column now feeds only the rail geometry (`SpaceMountainTrackRenderer`). See `SPACE-MOUNTAIN-PROGRESS.md`. This is the inverse of the project's original worry — rather than deleting SM banking as redundant with SC, it now *rides on* SC.

Resources (`src/main/resources/imaginemorefun/`):
- `dome_track.bin` — SM/HSM track (IFTC v2). Pre-existed the project.

Per-ride track CSVs from the abandoned project still live in `<gameDir>/debug-dumps/` (one per ride session) and intermediate bins in `~/imf-debug-dumps-archive/`. Not in the repo.

## What was removed

- `CoasterTrackRecorder.java` (the DebugBridge dev tool that captured CSVs)
- `track_btm.bin`, `track_rsr.bin`, `track_gadget.bin` (baked track binaries)
- Config fields, defaults, Cloth UI groups, and lang strings for BTM / RSR / Gadget / Matterhorn / Incredicoaster banking
- `RecordingSource` enum + `RIDE_SOURCES` map (added for Gadget's VEHICLE-source recording before that ride was dropped)
- `bankStartSeconds` field on `CoasterEntry` (added for RSR's 180s scenic-drive gate before RSR was dropped)

## Lessons

- **Check SmoothCoasters coverage first.** Before doing any coaster-banking work on a ride with SC active, observe the horizon during a vanilla ride: if SC is already tilting, our banking would just double-apply. The SC mod ships with PrismLauncher's ImagineFun instance under `mods/`.
- **Position-indexed track lookups are correct regardless of recording source.** `nearestSample(seat)` returns the right roll whether the bake was from seat or car-body, so the seat-row sensitivity that drove the original car-body-only recorder design was a red herring for most rides.
- See [[project_smoothcoasters_overlap]] memory for the SC coverage table to consult before re-opening any of this work.
