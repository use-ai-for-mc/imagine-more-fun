# AGENTS.md

This is the authoritative instruction entrypoint for AI agents working in this repository. Do not
maintain tool-specific copies of these rules; route them here instead.

## Working contract

- Reply to the user in Chinese unless they request another language.
- Continue through implementation and proportionate verification; do not stop at a plan when the
  requested work is safely executable.
- Preserve dirty and untracked work. Never reset, discard, or reformat unrelated changes.
- Treat the current checkout and code as authoritative. Dated validation records and archived
  progress documents are evidence, not proof of the current build or runtime state.
- Do not claim that a running Minecraft client loaded a newly deployed JAR. The client must be fully
  restarted before JVM or bundled-native changes take effect.

## Quick start

```bash
./gradlew test                     # Automated JUnit tests under src/test/java
./gradlew spotlessCheck            # Google Java Format verification
./gradlew build                    # build/libs/imaginemorefun-<mod_version>.jar
```

Gameplay, rendering, mixin compatibility, server packets, and native-helper behavior also require
manual in-game testing on ImagineFun. Build success is not sufficient evidence for those paths.

## Deploy

Deploy only when the user explicitly asks:

```bash
./build-and-deploy.sh
```

The script rebuilds the macOS and Windows native helpers, runs checks, verifies the JAR, clears
cached helper binaries, removes superseded mod JARs, and atomically swaps the current JAR into the
PrismLauncher `ImagineFun` instance. Never replace the deployed JAR with plain `cp`; a running JVM
may still hold the prior JAR inode open. See
[`docs/operations/RELEASE_AND_DEPLOY.md`](docs/operations/RELEASE_AND_DEPLOY.md).

## Architecture at a glance

ImagineMoreFun is a client-side Fabric 26.2 mod for `*.imaginefun.net`. `ImfClient` is the single
Fabric entrypoint. It runs storage migration, registers Space Mountain helpers, initializes NRA,
PIM, and SkinCache, then initializes Canoe Helper.

| Area | Package | Responsibility |
|---|---|---|
| NRA | `com.chenweikeng.imf.nra.*` | Ride tracking, alerts, strategy/daily-plan HUDs, reports, audio, visual helpers |
| PIM | `com.chenweikeng.imf.pim.*` | Pin collection, valuation, trading, pack overlays |
| SkinCache | `com.chenweikeng.imf.skincache.*` | Local skin texture/profile cache |
| Shared | `com.chenweikeng.imf.*` | Entrypoint, storage, migration, shared file I/O |

All mixins are registered in `src/main/resources/imf.mixins.json`. Read
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) before changing initialization, lifecycle, storage,
threading, or module boundaries.

## Server gating and storage

- `ServerState.isImagineFunServer()` combines the ImagineFun host check with NRA's `globalEnable`.
- `PimClient.isImagineFunServer()` is a separate host-only gate.
- NRA state lives under `<configDir>/imaginemorefun/` and is registered in `ImfStorage`.
- PIM keeps compatibility filenames at the config root: `pim_config.json`, `pim_pin_book.json`,
  `pim_pin_detail.json`, and `pim_pin_rarity.json`.
- SkinCache owns `<gameDir>/skincache/` and does not use `ImfStorage`.

Never introduce `new File("config/...")`; resolve paths through Fabric Loader and `ImfStorage`.

## Documentation routing

Start at [`docs/README.md`](docs/README.md). It distinguishes current contracts, observational
research, dated validation evidence, and archived history. When code and a current document differ,
verify the code and update the document in the same change. Do not use files under `docs/archive/`
as implementation instructions.

## Key dependencies

- Cloth Config: required configuration UI.
- ModMenu: optional, compile-only integration.
- Monkeycraft API: optional, compile-only; calls must remain guarded by `MonkeycraftCompat`.
- SmoothCoasters: optional runtime integration through the `@Pseudo` camera-tilt mixin.
- Native WebView/status helpers: built from `native/` and embedded under
  `src/main/resources/native/` during release builds.
