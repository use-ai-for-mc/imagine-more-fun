# Seasonal ride identity observation, 2026-10-02

This is live source-data evidence from the existing Minecraft 26.2 client on ImagineFun,
captured through DebugBridge. The changed IMF code was not deployed or runtime-tested.

## Haunted Mansion Holiday

The sidebar displayed ` | Haunted Mansi...`, while the loaded IMF resolved
`CurrentRideHolder` to `HAUNTED_MANSION`. The session API already distinguished
`hm` (700 lifetime rides) from `hmh` (159 lifetime rides).

A passive ImagineFunUtils `RIDE_STATUS` listener observed:

```text
receivedAtEpochMs=1790928834175
RideStatusPayload[rideId=hmh, displayName=Haunted Mansion Holiday, riding=false,
  startedAtEpochMs=1790928388877, durationMs=445150]

receivedAtEpochMs=1790928869019
RideStatusPayload[rideId=hmh, displayName=Haunted Mansion Holiday, riding=true,
  startedAtEpochMs=1790928868877, durationMs=0]
```

These are the end of one ride and the start of the following ride, not a complete
start/end capture of the same ride. The completed ride appeared in recent rides as
`RecentRide[rideId=hmh, duration=445150, time=2026-10-02 01:13:54]`; the recent API
timestamp is server-local America/Los_Angeles time.

After completion, server lifetime counts were `hm=700`, `hmh=160`. The old client's
local counts were `hm=702`, `hmh=160`, demonstrating ordinary-ride overcount from
the ambiguous sidebar. The local `hm` count was corrected to the current server
value 700 through `RideCountManager.updateRideCount()` and persisted with
`forceSave()`. Future rides on that old client can still reproduce the bug until
the new artifact is deployed and the client fully restarted. The passive observer
was disabled after capturing the subsequent start.

## Guardians of the Galaxy

The session API initially contained separate lifetime statistics for `guardians=1001`
and `gotgmad=36`. The player subsequently boarded Monsters After Dark. The observer
was re-enabled about seven seconds into the ride, so its start event was missed.
The sidebar showed ` | Guardians of ...`, and the old client resolved Mission Breakout.
The end event was captured:

```text
receivedAtEpochMs=1790930227804
RideStatusPayload[rideId=gotgmad,
  displayName=Guardians of the Galaxy: Monsters After Dark, riding=false,
  startedAtEpochMs=1790930104228, durationMs=123419]
```

Afterward, `gotgmad` increased from 36 to 37 and total time from 4442485 to 4565904
milliseconds, exactly the event duration. `guardians` remained 1001 with unchanged
time. Recent rides contained `RecentRide[rideId=gotgmad, duration=123419,
time=2026-10-02 01:37:07]`. This confirms the current seasonal completion ID and
independent scoring; it does not constitute a complete start/end capture.

The local old-client `guardians` count became 1002 despite the unchanged server
count 1001. It was corrected to the freshly queried server value and force-saved.

The existing TOT autograb center is (-344.30, -746.35), y=64, in `retro`.
The former Guardians center was (-338.06, -723.80), about 23.4 blocks away.
The observed post-completion position was (-335.70, 64, -724.70), near the
former Guardians region. These data do not establish the active boarding trigger.
The user's final request for this version was to remove autograb for both Mission
Breakout and Monsters After Dark. Neither has an autograb resource entry in the
resulting checkout; TOT keeps its existing region in `retro`.

## Checkout verification

`./gradlew test spotlessCheck build` passed: 113 tests, zero failures or errors.
Coverage includes seasonal/ordinary switching, end state, connection reset,
unknown IDs, elapsed time, and ambiguous sidebar fallback. This does not prove
the modified callbacks or HUD behavior in a restarted client.

## Plan and local deployment follow-up

The raw `/v1/session/rides` response was inspected: top-level keys were `rides`,
`overall`, `weekly`, and `yearly`; the `hmh`, `gotgmad`, and `hyperspace` entries
contained only `overall`/`weekly`/`yearly` count and time totals. No open/closed
field was present. Hyperspace historical statistics were still returned.

The unchanged live plan generator was checked in one game-thread operation with
temporary seasonal un-hiding, then the original settings immediately restored:
both `hmh` and `gotgmad` were eligible and strategy goals read their independent
counts. This verifies the existing generator/goal data path, not a new-build HUD.

At the user's request, the running client's Rides settings were then persisted
with `hmh` and `gotgmad` visible and `hm` and `guardians` hidden. Eligibility was
confirmed as `gotgmad, hmh` for those four identities. The freshly queried server
counts at that point were `hm=700`, `hmh=162`, `guardians=1001`, `gotgmad=43`;
ordinary counts inflated by further old-client seasonal rides were reconciled.

`./build-and-deploy.sh` rebuilt both macOS helpers and both Windows helpers,
passed checks, cleared helper caches, and atomically deployed version 3.4.2 to
the ImagineFun Add-Ons Prism instance. Source and deployed JAR SHA-256 matched:
`419421815618466241eae39cb524c110dfd6a6897c784566c6ea639a9cd36b15`.
Archive integrity, inclusion of `ServerRideState`, native helper resources, and
absence of both Guardians autograb entries were verified. Full client restart
and new-build in-game acceptance remain pending the user's test.

## Subsequent user-requested calendar and task compatibility

The user subsequently specified October-only Monsters After Dark and October 1 through
January 15 inclusive Haunted Mansion Holiday for both ride-plan and Strategy Hub recommendations.
The user also requested that either member of each family advance the other's local plan task.
The checkout now implements those calendar choices, independent lifetime counts, summed positive
family count deltas for local tasks, companion highlighting, and stored-plan baseline migration.
The strategy current-riding row still follows the actual server event. Imported server objective
names are retained; local task compatibility does not change server objective completion.

121 tests passed, including calendar boundaries, cross-year behavior, dual-version progression,
missing companion baselines, history/quest preservation, and duplicate elimination. The standard
deployment script rebuilt native helpers and atomically replaced 3.4.2 again. The latest source
and deployed JAR hashes match:
`dd567fa80b6798038426a807dd770c7de7ce18b9e27dddc0cd0ba89f00169376`.
The archive and the three new scheduling/progress classes were verified. The prior hash above
identifies the earlier artifact, not this final version. Restarted-client acceptance is pending.
