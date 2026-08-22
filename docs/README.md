# AI documentation map

These documents are optimized for coding agents. They are organized by authority so historical
notes cannot silently override current code.

## Authority order

1. Current checkout, tests, Gradle metadata, and `fabric.mod.json`.
2. Root [`AGENTS.md`](../AGENTS.md) for workflow and safety rules.
3. Current architecture, feature, and operations documents listed below.
4. `research/` for observational data that must be revalidated before implementation.
5. `validation/` for dated evidence about a specific artifact or live observation.
6. `archive/` for superseded roadmaps and decision history.

If two sources disagree, inspect the implementation and update the current document. Never rewrite
a dated validation record to make it look current.

## Current contracts

| Task | Read first |
|---|---|
| Entrypoint, modules, events, storage, mixins, threading | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| Build, local release, Prism deployment, runtime-load boundary | [`operations/RELEASE_AND_DEPLOY.md`](operations/RELEASE_AND_DEPLOY.md) |
| OpenAudioMC lifecycle or native helper | [`features/OPENAUDIO_LIFECYCLE.md`](features/OPENAUDIO_LIFECYCLE.md) |
| Daily ride plan or Daily Objectives integration | [`features/DAILY_RIDE_PLAN.md`](features/DAILY_RIDE_PLAN.md) |
| Space/Hyperspace Mountain rendering, audio, resources, tilt | [`features/SPACE_MOUNTAIN.md`](features/SPACE_MOUNTAIN.md) |
| PIM UI, persistence, handlers, calculation and trading | [`features/PIM.md`](features/PIM.md) |

The root [`README.md`](../README.md) is the product overview. `docs/index.html` is a GitHub Pages
redirect for the retired Red Car Trolley page, not engineering documentation.

## Research

- [`research/PIN_ITEM_DATA.md`](research/PIN_ITEM_DATA.md): observed pin-item/NBT structures and
  exploratory interaction notes. Revalidate live data before changing parsers.

## Dated validation evidence

- [`validation/IMAGINEFUN_RIDE_IDS_2026-07.md`](validation/IMAGINEFUN_RIDE_IDS_2026-07.md):
  ImagineFunUtils 0.0.8 ride lifecycle observations.
- [`validation/IMAGINEFUN_API_SESSION_CAPTURE_2026-08-22.md`](validation/IMAGINEFUN_API_SESSION_CAPTURE_2026-08-22.md):
  API-session timing, authenticated stats, Splash Mountain duration, and fresh-install ingestion
  behavior.
- [`validation/RCT_GOOD_CAR_CAPTURE_2026-08-20.md`](validation/RCT_GOOD_CAR_CAPTURE_2026-08-20.md):
  good Red Car Trolley lifecycle, route, stops, and cycle calibration.
- [`validation/RCT_BAD_CAR_CAPTURE_2026-08-21.md`](validation/RCT_BAD_CAR_CAPTURE_2026-08-21.md):
  defective Red Car Trolley terminal behavior and missed outbound scoring.
- [`validation/CANOE_CAPTURE_2026-08-21.md`](validation/CANOE_CAPTURE_2026-08-21.md):
  Davy Crockett's Explorer Canoes API lifecycle and composite-entity route capture.
- [`validation/OPENAUDIO_LEGACY_GUARDED_2026-08-08.md`](validation/OPENAUDIO_LEGACY_GUARDED_2026-08-08.md):
  isolated and deployed-artifact evidence for one OpenAudioMC guard build.

Validation documents describe what was verified on their date. Their hashes, process state, server
IDs, and deployment status must not be reused as current facts without a fresh check.

## Archive

Files under [`archive/`](archive/) preserve abandoned experiments, shipped roadmaps, and old design
logs. They are useful for rationale and archaeology only:

- `DAILY_RIDE_PLAN_ROADMAP.md`
- `COASTER_BANKING_EXPERIMENT.md`
- `SPACE_MOUNTAIN_PROGRESS_2026-05.md`
- `SPACE_MOUNTAIN_DESIGN_NOTES_2026-05.md`

New current behavior belongs in a feature document, not in an archived progress log.
