# ImagineMoreFun

A quality-of-life mod for the **ImagineFun** Minecraft server. It adds a ride tracker with goal HUDs, a daily ride planner, pin-collection tooling, visual and audio tweaks, and a themed Space Mountain overlay — all to make long sessions on ImagineFun smoother.

Every feature only activates while you are connected to an `*.imaginefun.net` server. On any other server, or in single-player, the mod stays dormant.

> **Replaces Not Riding Alert and Pim!**
>
> ImagineMoreFun merges the **Not Riding Alert** and **Pim!** mods into one — it already does everything those two did. Remove both before installing ImagineMoreFun; running it alongside either one will conflict. Your existing Not Riding Alert settings are imported automatically on first launch.

## Requirements

- Minecraft **1.21.11** with the **Fabric** loader
- Java 21

## Features

### Ride tracking & planning

- **Not-riding alert** — an optional sound when you are not on a ride, with a minimum-ride-time filter.
- **Ride tracker** — an on-screen list of rides and your progress toward a goal (1K / 5K / 10K), with multiple layouts, sort rules, closest-ride highlighting, and customizable colors. Show it always, only while riding, only while not riding, or never.
- **Ride plan** — a chained "do this, then that" daily plan across the top of the screen, with live progress and automatic extension as you finish steps. The server's Daily Objectives are folded in automatically.
- **Daily ride report** — a per-day summary screen, with popup or chat reminders.
- **Session stats** — today's ride count, ride time, rides-per-hour, and streak.
- **Advance-notice chime** — a sound a few seconds before a ride ends.

### Convenience

- **Autograbbing aids** — outlines for autograb regions, automatic cursor release, optional window-minimize, and PC hibernation while riding.
- **Setup wizard** — a guided first-run walkthrough.
- **Config profiles** — named saved settings you can switch between instantly.

### Visuals

- **Screen tweaks** — dim-while-riding, fullbright, modernized closed captions, and toggles to hide the scoreboard, chat, health bar, name tags, hotbar, XP level, and love-potion messages.
- **Firework viewing** — alerts, time-of-day changes, and blindness are suppressed inside the firework area.
- **Space Mountain enhancements** — a client-side overlay for Space Mountain / Hyperspace Mountain rides: a cleaned-up dome, an animated launch-tunnel screen effect, the coaster track, a projected starfield, and hidden show props. On by default; a master toggle reverts to vanilla visuals.

### Pin collecting

- **Pin-book tooling** — value and fair-market-value lookup, a trade helper, and pending-pin tracking, with export and reset.
- **Pin screen** — a combined UI with click-to-copy, click-to-trade, and click-to-reset.
- **Overlays** — a rarity / alpha table on container screens, and color analysis for pin packs.
- **Pin hoarder** — auto-confirms pin-trade dialogs.
- **Trader warping** and scoreboard pin highlights.

### Audio & extras

- **OpenAudioMC integration** — connect and disconnect the server's web-audio sessions in-game, with optional auto-connect and an audio-boost reminder.
- **Status-bar indicator** — a ride countdown in the macOS menu bar or Windows system tray.
- **Skin caching** — caches player skins locally to cut down on repeated downloads.

## Commands

- `/imf` — open the profile manager
- `/imf setup` — (re-)run the first-run walkthrough
- `/imf profile <name>` — switch to a saved settings profile
- `/ridereport [date]` — open the daily ride report
- `/oa connect | disconnect | reconnect | volume` — OpenAudioMC controls
- `/pim` — open the pin UI
- `/pim:compute`, `/pim:trade`, `/pim:reset`, `/pim:export`, `/pim:value`, `/pim:fmv` — pin tooling

## Building from source

```bash
./gradlew build
```

The built mod jar lands in `build/libs/`.

## License

ImagineMoreFun is released under the [MIT License](LICENSE). The bundled audio assets keep their own licenses (CC BY 3.0 and the Mixkit license) — see Audio credits below.

## Audio credits

The Space Mountain ride overlay ships two looping sound assets:

- **`rail_friction.ogg`** — trimmed and re-encoded from "train_wheels_ringing_speed.wav" by **uair01** on [Freesound](https://freesound.org/people/uair01/sounds/57787/), licensed under [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/).
- **`wind.ogg`** — trimmed and re-encoded from "Wind cold interior" on [Mixkit](https://mixkit.co/free-sound-effects/wind/), used under the [Mixkit Sound Effects Free License](https://mixkit.co/license/#sfxFree).
