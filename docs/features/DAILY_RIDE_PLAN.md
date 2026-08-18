# Daily ride plan

## Current user surface

The daily plan is a top-of-screen HUD. It has no `/rideplan` command, full-window plan screen, or
keybind in the current code. Configuration controls whether the HUD is shown and reuses strategy
HUD visibility rules such as boss-bar suppression and tracker display mode.

The HUD shows a sliding chain of layers. Layers can be single rides or branching groups, display
current progress, highlight the active ride, animate the active connector, and emit sound/particle
celebrations on completion. The strategy HUD yields while the daily-plan HUD is active.

## Model and progression

- `DailyPlanManager` loads or creates the local day's plan and migrates legacy node-only plans.
- `DailyPlanGenerator` generates layers, observes ride filters/max goals, incorporates Daily
  Objectives, and keeps enough unfinished tail capacity that the chain can continue.
- `DailyPlanProgressTracker` updates from `RideCountManager` deltas.
- The first incomplete ordinary layer is active and receives its own baseline counts.
- Daily Objective layers may carry baselines from capture and can progress ahead of the normal
  active layer; non-ride objectives reconcile when a fresh objective snapshot is captured.
- `DailyPlanCelebration` owns completion sounds and particles. Routine completion is intentionally
  not mirrored as noisy chat output.

## Persistence

| Path | Owner |
|---|---|
| `<configDir>/imaginemorefun/nra-daily-plan.json` | Generated layers, baselines, progress, completion state |
| `<configDir>/imaginemorefun/nra-daily-quests.json` | Captured Daily Objectives snapshot/state |

Paths are supplied by `ImfStorage`. Local-day rollover follows `LocalDate.now()` and regenerates the
plan while server Daily Objectives may persist independently.

## Code map

- `nra/dailyplan/DailyPlanManager.java`: lifecycle, migration, pruning, objective injection.
- `nra/dailyplan/DailyPlanGenerator.java`: eligible rides and layer generation.
- `nra/dailyplan/DailyPlanProgressTracker.java`: count-based completion and tail extension.
- `nra/dailyplan/DailyPlanHudRenderer.java`: HUD layout, animation, visibility.
- `nra/dailyplan/DailyQuestParser.java`: server objective parsing.
- `nra/dailyplan/DailyPlanStorage.java`: persistence.

The old staged implementation checklist is preserved only in
[`../archive/DAILY_RIDE_PLAN_ROADMAP.md`](../archive/DAILY_RIDE_PLAN_ROADMAP.md). It is not a backlog
and includes commands/UI concepts that were removed before the current design.
