# ImagineMoreFun

A merged quality-of-life mod for the ImagineFun Minecraft server, bundling three previously-separate mods into a single Fabric 1.21.11 JAR:

- **Not Riding Alert** — ride tracking, strategy HUD, alerts, audio integration, daily reports, and more. See `com.chenweikeng.imf.nra.*`.
- **Pim!** — pin collection visual highlights, trader warping, and pin-book tooling. See `com.chenweikeng.imf.pim.*`.
- **SkinCache** — local caching of player skin textures to reduce Mojang CDN traffic. See `com.chenweikeng.imf.skincache.*`.

All features activate only while connected to an `*.imaginefun.net` server.

## Target

- Minecraft **1.21.11** (Fabric)
- Java 21

## Build

```bash
./gradlew build
# → build/libs/imaginemorefun-3.0.1.jar
```

## Features

### NRA — Not Riding Alert

- **Alert when not riding** — configurable alert sound with a minimum-ride-time filter.
- **Ride tracker / Strategy HUD** — on-screen list of rides toward a configurable goal (1K / 5K / 10K), with V0 / V1 / V2 layouts, sortable by several rules, optional closest-ride highlight, customizable colors, and configurable background opacity. Display can be always / only-when-riding / only-when-not-riding / never, and filtered to only autograbbing rides.
- **Ride Plan HUD** — chained "do this, then that" daily plan rendered top-center: SINGLE / OR / AND layers with live progress, animated power flow, and automatic tail extension as you complete steps. Daily Objectives parsed from the server's quest GUI are auto-injected as gold `★ DAILY` layers, so each new layer added after a finish prefers a pending quest whose ride isn't already pinned. Selected via the Ride Hub Mode config setting (alternative to the Strategy HUD).
- **Setup wizard** — guided first-run tutorial (`/imf setup`) covering alerts, visuals, autograbbing, UI hiding, ride selection, tracker, session extras, and profiles.
- **Config profiles** — named saved configs with history, diff summaries, and one-command switching (`/imf profile <name>`).
- **Ride report** — daily ride summary screen (`/ridereport [date]`), plus popup / chat / silent notification modes.
- **Session stats HUD** — daily rides, ride time, rides-per-hour, and streak in the bottom-right.
- **Advance-notice sounds** — per-ride chime N seconds before a ride ends.
- **Autograbbing aids** — visual outlines of autograb regions, automatic cursor release on autograb or ride start, optional window minimize on autograb or ride start, and PC hibernation while riding.
- **Visual tweaks** — dim-when-riding, fullbright mode (none / riding / not-riding / always), modernized closed captions (move `[CC]` messages out of chat), hide scoreboard / chat / health / name tags / hotbar / XP level / love-potion messages.
- **Firework viewing** — alerts, time-of-day reset, and blindness are all suppressed inside the firework area.
- **OpenAudioMC integration** — `/oa connect|disconnect|reconnect|volume` plus optional auto-connect, headless browser audio session, and an audio-boost reminder.
- **Native status-bar indicator** — countdown helper in the macOS menu bar / Windows system tray, driven by a native helper bundled with the mod.

### PIM — Pin tooling

- **Pin-book tooling** — value / FMV lookup, trade helper, compute pending-pin view with hide-empty, export, and reset (`/pim:compute`, `/pim:trade`, `/pim:reset`, `/pim:export`, `/pim:value`, `/pim:fmv`).
- **Unified PIM screen** — `/pim` opens a combined UI with click-to-copy / click-to-trade / click-to-reset hit regions.
- **Alpha table overlay** — rarity/alpha info rendered on top of container screens, anchored to the right edge.
- **Pin pack overlay** — color analysis and visual overlay for pin packs.
- **Pin hoarder** — auto-confirm helper for pin-trade dialogs.
- **Boss-bar / trader warping** and scoreboard pin highlights (inherited from upstream Pim!).

### SkinCache

- Local caching of player skin textures and skull-block skins to reduce Mojang CDN traffic.

## Commands

- `/imf` — open Profile Management screen
- `/imf setup` — (re-)run the first-run tutorial
- `/imf profile <name>` — switch to a saved config profile
- `/ridereport [date]` — open the daily ride report
- `/oa connect | disconnect | reconnect | volume` — OpenAudioMC controls
- `/pim` — open the unified PIM UI
- `/pim:compute`, `/pim:trade`, `/pim:reset`, `/pim:export`, `/pim:value`, `/pim:fmv` — pin tooling

## Storage

- NRA data: `config/imaginemorefun/` (automatically migrated from `config/not-riding-alert*.json` on first launch)
- SkinCache data: `<gameDir>/skincache/` (unchanged from upstream)
- Pim has no disk state

## Upstream

This is a fork. The three upstream repos (`not-riding-alert`, `pim`, `skin-cache-mod`) are no longer tracked. See `LICENSE` for attribution.

## Layout

```
com.chenweikeng.imf                 — umbrella (ImfClient, ImfStorage, ImfMigration)
com.chenweikeng.imf.nra.*           — NRA sources (except mixins)
com.chenweikeng.imf.pim.*           — PIM sources (except mixins)
com.chenweikeng.imf.skincache.*     — SkinCache sources (except mixins)
com.chenweikeng.imf.mixin.*         — all mixin classes, flat, prefixed by origin
```

A single `imf.mixins.json` references every mixin under `com.chenweikeng.imf.mixin`.

## License

Mixed CC0-1.0 (NRA + PIM + umbrella) and MIT (skincache). See `LICENSE` and `LICENSE-CC0`.

## Audio credits

The Space/Hyperspace Mountain ride overlay (`com.chenweikeng.imf.nra.spacemountain.SpaceMountainRideAudio`) ships two looping sound assets:

- **`assets/imaginemorefun/sounds/ride/rail_friction.ogg`** — trimmed and re-encoded loop derived from "train_wheels_ringing_speed.wav" by **uair01** on [Freesound](https://freesound.org/people/uair01/sounds/57787/). Licensed under [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/).
- **`assets/imaginemorefun/sounds/ride/wind.ogg`** — trimmed and re-encoded loop derived from "Wind cold interior" on [Mixkit](https://mixkit.co/free-sound-effects/wind/). Used under the [Mixkit Sound Effects Free License](https://mixkit.co/license/#sfxFree).
