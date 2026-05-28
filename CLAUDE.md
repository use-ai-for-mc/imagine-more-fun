# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew build                    # Build mod → build/libs/imaginemorefun-3.1.5.jar
./gradlew spotlessApply            # Format code (Google Java Format)
./gradlew spotlessCheck            # Check formatting without fixing
```

No tests exist in this codebase. Testing is done manually in-game on the ImagineFun Minecraft server.

## Deploy

`build-and-deploy.sh` builds the mod and atomically swaps the JAR into the PrismLauncher `ImagineFun` instance — the atomic swap keeps a running game's open JAR handle valid, so never plain-`cp` the jar in:
```bash
./build-and-deploy.sh
```

## Architecture

This is a **client-side Fabric 1.21.11 mod** targeting the ImagineFun Minecraft server (`*.imaginefun.net`). It merges three formerly-separate mods:

### Sub-modules

| Module | Package | Purpose |
|--------|---------|---------|
| **NRA** (Not Riding Alert) | `com.chenweikeng.imf.nra.*` | Ride tracking, alerts, strategy HUD, audio integration, daily reports |
| **PIM** | `com.chenweikeng.imf.pim.*` | Pin collection highlights, trader warping, pin-book tooling |
| **SkinCache** | `com.chenweikeng.imf.skincache.*` | Local caching of player skin textures |

### Entrypoint

`ImfClient` is the single Fabric entrypoint. It runs storage migration once, then initializes all three sub-mods in order: NRA → PIM → SkinCache. The sub-mods are independent of each other.

### Mixins

All mixins live flat in `com.chenweikeng.imf.mixin.*` with prefixes indicating origin:
- `Nra*` — NRA mixins
- `Pim*` — PIM mixins  
- `SkinCache*` — SkinCache mixins
- `Canoe*` — canoe-helper mixins
- `Imf*` — Shared/umbrella mixins

Registered in single `imf.mixins.json`.

### Server Detection

Features only activate when connected to ImagineFun. Both checks verify `serverIp.endsWith(".imaginefun.net")`:
- `ServerState.isImagineFunServer()` — NRA's centralized check; additionally gated by `ModConfig.currentSetting.globalEnable`
- `PimClient.isImagineFunServer()` — PIM's check; pure IP suffix only

### Storage

- NRA config: `config/imaginemorefun/` (migrated from old `config/not-riding-alert*.json`)
- SkinCache: `<gameDir>/skincache/`
- PIM: No persistent state

## Key Dependencies

- **Cloth Config** — Required for config screens
- **ModMenu** — Optional, compile-only (config screen integration)
- **Monkeycraft API** — Optional, compile-only (guarded by `MonkeycraftCompat.isAvailable()`)
