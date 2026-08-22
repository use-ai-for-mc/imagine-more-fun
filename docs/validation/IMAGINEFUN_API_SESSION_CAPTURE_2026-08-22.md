# ImagineFunUtils API session and ride-stat capture — 2026-08-22

## Scope and evidence

This capture exercised ImagineFunUtils 0.0.8 with the deployed IMF build from commit `5f8dc6f`.
It observed API-session establishment, an authenticated `getSessionStats()` request, disconnect and
rejoin behavior, and one complete Splash Mountain lifecycle.

The paused raw capture remains outside the repository at:

`/Users/cusgadmin/Library/Application Support/PrismLauncher/instances/ImagineFun/.minecraft/config/imaginemorefun/api-observations/api-sampling-1787365825585.ndjson`

It contains 39 NDJSON rows and has SHA-256
`61ffd0051396fd5d6dd19fa3f85727d06d111c4c0e1907bb214081d9828e37e4`. The raw file was not
committed because complete session-stat responses contain player-specific lifetime statistics.
This document retains only the fields needed to validate client behavior.

## Session establishment and 401 boundary

On rejoin, the server events arrived in this order:

| Event | Received epoch (ms) | State at callback |
| --- | ---: | --- |
| `SERVER_INFO` | 1787368241371 | `ServerSession` connected; `ApiSession` inactive |
| `SESSION_UPDATED` | 1787368241377 | both sessions active |

The events were only 6 ms apart, but that gap was observable and significant:

- a `getSessionStats()` request started from the `SERVER_INFO` callback at 1787368241373 returned
  HTTP 401;
- a request started from `SESSION_UPDATED` succeeded and returned 191 statistic keys; and
- the first request still returned 401 after the API session had become active, confirming that
  authorization is fixed when the HTTP request is constructed rather than repaired later.

Client code must therefore gate typed API calls on `ApiSession.isActive()` or react to
`SESSION_UPDATED`. `ServerSession.isConnected()` and `SERVER_INFO` alone are insufficient.
Disconnect made both sessions inactive, cleared the server identity, and caused a direct
`getSessionStats()` call to fail with HTTP 401.

## Splash Mountain lifecycle

| Boundary | Observed value |
| --- | ---: |
| Start event received | 1787368471349 ms |
| `startedAtEpochMs` | 1787368471145 ms |
| Start-event `durationMs` | 0 ms |
| Start event transport/dispatch lag | 203 ms |
| Mid-ride stats request | 1787368479420 ms |
| End event received | 1787368955353 ms |
| End-event `durationMs` | 484000 ms |
| End event minus calculated completion | 208 ms |

The end event repeated the exact start epoch. Its `startedAtEpochMs + durationMs` was within 208 ms
of local receipt, so these are server-defined millisecond values suitable for ride-lifecycle
display and validation. The start payload does not expose a predicted duration: its `durationMs`
is zero until completion.

Selected `getSessionStats()` values changed as follows:

| Statistic | Ride start | Immediate ride end | Change |
| --- | ---: | ---: | ---: |
| `playtime_total` | 16775386 | 16775938 | +552 |
| `distance_walked` | 1219227 | 1219227 | 0 |
| `ride_photopass_splash` | 562 | 563 | +1 |
| `fastpasses_used` | 2516 | 2516 | 0 |

The 552-second playtime increase did not equal the 484-second ride duration. Session statistics are
long-lived counters with their own refresh/aggregation behavior and should not replace
`ride_status.durationMs` as the authoritative duration of an individual ride.

The collector also observed that disconnecting during a separate active Splash Mountain did not
produce a synthetic `riding=false` completion. A later unmatched `riding=true` event was confirmed
by the player to be an intentionally started new ride, not a duplicate callback.

## Current IMF ingestion behavior

`ImagineFunUtilsRideDataSource` waits for an active API session, then fetches
`getSessionPlayer()` and `getSessionRides()` together. It validates that the API player UUID matches
the active Minecraft player and applies recognized overall counts to `RideCountManager` on the
Minecraft client thread.

- `nra-rides.json` stores only recognized lifetime counts.
- A successful snapshot normally runs immediately after `SESSION_UPDATED`; the join fallback
  begins after 3 seconds if that event is not observed.
- Successful snapshots refresh every 60 seconds and 1.5 seconds after a ride-end event.
- Transient failures retain cached values and retry with bounded backoff.
- Server counts are merged monotonically: higher/missing counts update local state, while lower
  values do not erase optimistic or cached counts.
- The periodic client-tick save persists newly imported counts atomically. On an empty local state,
  the first successful snapshot is eligible to save on the following tick.
- Unknown API ride IDs are logged once and ignored rather than merged into another ride.
- Without ImagineFunUtils, or before the first successful API snapshot on a connection, the legacy
  `/ridestats` parser remains available.

This means a fresh install with ImagineFunUtils 0.0.8 and a healthy API session should reconstruct
all currently recognized lifetime ride counts without opening the rides menu. It does not restore
weekly/yearly durations, raw recent-ride history, historical unknown rides, daily-plan baselines, or
dated report snapshots because those are not part of `nra-rides.json`.
