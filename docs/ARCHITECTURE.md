# Current architecture

This document describes the current structural contracts of the checkout. It should change with
code that alters initialization, module ownership, storage, event lifecycles, or concurrency.

## Runtime and entrypoint

- Client-side Fabric mod for Minecraft 26.2 and Java 25.
- Mod ID and archive base name: `imaginemorefun`.
- Single Fabric client entrypoint: `com.chenweikeng.imf.ImfClient`.
- `ImfClient.onInitializeClient()` runs in this order:
  1. `ImfMigration.runOnce()`.
  2. Space Mountain renderer, overlay, tunnel, disco-ball, and ride-audio registration.
  3. `NotRidingAlertClient` (NRA).
  4. `PimClient`.
  5. `SkinCacheMod`.
  6. `CanoeHelperClient.init()`.

Do not assume the three former mods are still independently deployable. `fabric.mod.json` declares
that ImagineMoreFun breaks the old `not-riding-alert`, `pim`, and `skincache` mod IDs.

## Module ownership

### NRA

`com.chenweikeng.imf.nra.*` owns ride state/counts, alerts, HUDs, daily plans, reports, OpenAudioMC,
status-bar helpers, configuration profiles, server-aware visual changes, Space Mountain, and Canoe
Helper integration.

Important lifecycle owner: `NotRidingAlertClient` registers connection, tick, HUD, world-render,
command, and shutdown callbacks. Disconnect cleanup belongs there or in the subsystem method it
calls; do not add a second uncoordinated lifecycle owner.

NRA also owns quest collectible detection and highlighting. It registers the beam renderer and
the quest edge-glow HUD, which derives direction every frame from the current camera and nearest
beam origin while retaining the boss-bar distance gate. NRA maintains targets on client ticks, and clears them on disconnect. Particle hooks observe on the
client thread; highlights require a server quest boss bar displaying a distance and exclude PIM's local guidance bar.
While that bar is present and matching collectible dust is being rendered or a talk-to-NPC
exclamation-mark is loaded, the client sky is forced
to midnight; fullbright must not restore daytime.
See [`features/QUEST_COLLECTIBLES.md`](features/QUEST_COLLECTIBLES.md).

`ImagineFunWindowIconHandler` applies the ImagineFun logo to the macOS Dock or Windows taskbar on
an ImagineFun connection and restores the version-appropriate Minecraft icon on disconnect. It is
host-gated independently of NRA's `globalEnable` setting.

`RideStatsSourceCoordinator` owns lifetime ride-count ingestion. ImagineFunUtils 0.0.9 is optional:
when present, the coordinator reflectively loads the isolated `ImagineFunUtilsRideDataSource` and
prefers `getSessionRides()` snapshots for automatic synchronization. An explicit `/ridestats`
request remains authoritative after API startup, so its container data can immediately reconcile a
count when the API snapshot lags around ride completion. A transient API failure keeps the last
known counts. Stable server ride IDs map through `RideName.fromApiId()`; display names and IMF short
names are not API identifiers.

The same isolated bridge feeds connection-local `ServerRideState` from `RIDE_STATUS` events.
Once a status event has arrived, `ScoreboardHandler` publishes that event's stable ride ID and
server start timestamp instead of the sidebar name; an end event clears the current ride even
while a stale sidebar remains visible. Join/disconnect clears this authority. Before the first
event, or without ImagineFunUtils, the sidebar remains the fallback. Shared truncated prefixes
such as `Haunted Mansi...` and `Guardians of th...` resolve to `UNKNOWN`, rather than guessing the
ordinary variant. Seasonal identity follows each server event, without dates or persistent
seasonal switches; lifetime counts remain separate by their existing API IDs. Coordinate-based
nearest-ride suggestions and Strategy Hub recommendations follow the user's calendar via
`SeasonalRideSchedule`: October uses Monsters After Dark, and October 1 through January 15 uses
Haunted Mansion Holiday. That schedule is a planning assumption, not evidence of server availability.
Local ride-plan family tasks accept positive count deltas from either version while lifetime
counts remain separate. See [`features/DAILY_RIDE_PLAN.md`](features/DAILY_RIDE_PLAN.md).

Neither Guardians of the Galaxy variant currently has an autograb region in
`autograb-regions.json`, per the user's request for this version. Their server
ride identity and count tracking remain enabled. TOT retains its own `retro` region.

If the initial ImagineFunUtils handshake does not establish an API session, the isolated bridge
re-sends that handshake after 5, 15, and 35 seconds from joining, then stops. A session update
cancels pending retries immediately; cached counts and the legacy parser remain available if all
three recovery attempts fail.

Food consumption is internal report data. `FoodConsumptionTracker` recognizes `FOOD_TYPE`, confirms
a stack decrease, and records through `SessionTracker`; the intended user surface is the daily
summary/report, not per-item chat messages or routine INFO logging.

Automatic cursor release suppresses only Minecraft's `pauseIfInactive()` behavior while riding or
during the short restore grace period. It must not suppress `Window.onFocus(false)`: Minecraft
26.2's `TextInputManager` uses the real window-focus flag to stop changing the system IME after the
user switches to another application.

### PIM

`com.chenweikeng.imf.pim.*` owns the `/pim` client command and GUI, collection handlers, valuation,
trading, pin-pack overlays, and hoarder confirmation. Export and reset are GUI actions, not separate
client commands. See [`features/PIM.md`](features/PIM.md).

### SkinCache

`com.chenweikeng.imf.skincache.*` owns texture/profile caches under `<gameDir>/skincache/`.
`TextureCache.init()` initializes paths and indexes before performing cleanup. Do not reintroduce
cleanup from `SkinManager` construction.

## Server gates

NRA and PIM intentionally have different gates:

- `ServerState.onJoin()` records whether the normalized host ends with `.imaginefun.net`.
  `ServerState.isImagineFunServer()` also requires `ModConfig.currentSetting.globalEnable`.
- `PimClient.onJoin()` records the same host suffix independently.
  `PimClient.isImagineFunServer()` does not use NRA's global toggle.

Render and tick paths should exit early when their owning gate is false. Do not combine the two
state holders without auditing behavior when NRA is globally disabled.

## Storage

`ImfStorage` is the registry for shared/NRA and PIM paths. Paths must come from Fabric Loader rather
than the JVM working directory.

| Owner | Location | Notes |
|---|---|---|
| NRA | `<configDir>/imaginemorefun/` | Config, ride counts/snapshots, session, profiles, history, daily plan/quests, audio preference, migration markers |
| PIM | `<configDir>/pim_*.json` | Compatibility filenames remain at config root; resolved through `ImfStorage` |
| SkinCache | `<gameDir>/skincache/` | Texture/profile index, cached files, separate log |
| Native overrides/cache | `<configDir>/imaginemorefun/native/` | Cleared by the deployment script so bundled helpers re-extract on next launch |

`ImfMigration` only migrates legacy NRA paths. PIM root filenames are not migrated into the
ImagineMoreFun subdirectory.

Tutorial completion in `nra-tutorial.json` is keyed to an explicit tutorial version, not the mod
release version. A legacy file with `completed: true` and no tutorial-version field is accepted and
upgraded in place, including files whose old mod-version lookup was saved as `unknown`. Increment
the tutorial version only when a materially changed setup flow requires users to revisit it.

## Mixins and optional dependencies

All mixins live in `com.chenweikeng.imf.mixin.*` and are registered in `imf.mixins.json`. Prefixes
indicate the original owner (`Nra`, `Pim`, `SkinCache`, `Canoe`, `Imf`) but registration is shared.

ModMenu, Monkeycraft, ImagineFunUtils, and SmoothCoasters are optional. Monkeycraft calls must stay
behind `MonkeycraftCompat.isAvailable()`. ImagineFunUtils types must remain isolated in its
compatibility class, which is loaded only after Fabric Loader confirms the mod is installed. Mixins
targeting optional classes must remain soft/guarded; the SmoothCoasters integration uses a
`@Pseudo` string-target mixin so installations without it can still start.

## Concurrency boundaries

Most gameplay and rendering state belongs to the Minecraft client thread. Native audio work is the
main exception:

- ImagineFun API HTTP futures run on ImagineFunUtils worker threads. Completion, retry state, and
  `RideCountManager` mutations must return to the Minecraft client thread, and a connection
  generation must reject responses from an older server session.

- `OpenAudioMcService` uses a holder singleton and synchronizes publication/snapshot of the current
  `WebViewBridge`.
- Slow `WebViewBridge.start()` calls must run without holding the service monitor.
- Async callbacks must confirm they still target the current bridge/session before mutating state.
- A complete helper/WKWebView session is the audio recovery unit; do not reuse a failed WebView in a
  later logical session.

See [`features/OPENAUDIO_LIFECYCLE.md`](features/OPENAUDIO_LIFECYCLE.md) before changing recovery.

Monkeycraft's optional `INFO_PACKET` integration accepts the `openaudiomc` title and a boolean
`active` field. Native mobile ownership stops the desktop bridge and all automatic startup/recovery
while preserving connection intent and volume. Release resumes only a previously desired desktop
connection. Transport loss does not release ownership: mobile audio can survive locking or a brief
network interruption. The existing explicit desktop connect action can take ownership back after a
mobile crash. This latch is process-local; desktop-restart interaction still requires cross-device
acceptance. Browser external audio does not participate. Recovery tasks recheck ownership while
holding the service monitor before dispatching a new connection.

The 2026-09-21 local build was tested with real Minecraft 26.2 and the iOS 26.5 diagnostic simulator:
desktop remained stopped for approximately one minute including a controlled Monkeycraft socket
loss, then returned to the original desired connection and volume after mobile explicit disconnect.
This is service-level runtime evidence, not a physical-device audibility or normal GUI result.
Evidence: `../monkeycraft/outputs/closeout-2026-09-21/audio-*-samples*.json` from the repository parent.

## Verification boundary

JUnit covers pure policies, storage helpers, audio lifecycle/state, time formatting, and cache save
behavior. Automated tests do not prove mixin injection, rendering geometry, live server parsing,
native WebKit/CoreMedia behavior, or that a running Prism client loaded a deployed artifact.
