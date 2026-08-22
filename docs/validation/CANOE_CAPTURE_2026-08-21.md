# Davy Crockett's Explorer Canoes capture — 2026-08-21

## Scope and evidence

This capture observed one complete Davy Crockett's Explorer Canoes ride on ImagineFun D2. It was
the successful follow-up to an initial recorder that watched only the player's ordinary vehicle
state and therefore failed to identify the visible composite canoe body.

The successful raw capture remains at:

`/Users/cusgadmin/Library/Application Support/PrismLauncher/instances/ImagineFun/.minecraft/config/imaginemorefun/canoe-captures/canoe-entity-live-1787295593002.ndjson`

Paused-file SHA-256: `f83adb3a63a6b08cf6f85fce8f3c7e36c55b820e181d9b05264f6e3c6176149f`.

The file contains 21,835 lines: one session row, 21,832 20 Hz samples, and the two global
`ride_status` events. The listener reported no callback errors.

## Server and API lifecycle

| Boundary | Observed value |
| --- | ---: |
| Start event | `rideId=canoe`, epoch `1787296135724` ms |
| End event | `rideId=canoe`, duration `521100` ms |
| Event-to-event wall time | `521128` ms |
| Recent-ride entry | `canoe`, `521100` ms, `2026-08-21 00:17:36` |

After the ride, `getSessionRides()` returned the following dated totals:

| Window | Count | Time (ms) |
| --- | ---: | ---: |
| Overall | 312 | 102737944 |
| Weekly | 1 | 521100 |
| Yearly | 131 | 47759022 |

`ApiSession` and `ServerSession` reported inactive throughout the sampled ride and recovered about
2.9 seconds after the end event, at which point the client identified D2 and both API queries
succeeded. Despite those inactive accessors, the global `ride_status` listener received both
events. This is useful evidence that UI code should tolerate temporarily unavailable session API
snapshots without discarding lifecycle events already delivered by the event channel.

## Composite canoe identity

Each visible canoe was a ten-armor-stand composite rather than an original Minecraft `Boat`:

- two invisible, no-gravity endpoint armor stands rendered the custom model using a head-slot
  `minecraft:diamond_pickaxe` with damage 1131;
- the endpoints remained 7.66 blocks apart;
- eight invisible interior armor stands formed the body/seat line; and
- the player mounted a separate armor-stand seat (`78945259` in this client epoch).

The ridden canoe retained endpoint pair `16539/16540` for the complete capture. Runtime code should
discover the endpoint signature and pair geometry rather than hard-code these entity IDs, because
IDs are server- and client-epoch observations rather than product identifiers.

## Route validation

The composite-body center remained associated with the mounted seat for all 10,265 in-ride samples:

| Quantity | Observation |
| --- | ---: |
| Reference route length | `1153.21` blocks |
| Integrated observed center movement | `1145.86` blocks |
| Published progress | `0%` to `100%` |
| Median distance to reference path | `0.50` block |
| Maximum distance to reference path | `1.22` blocks |

One approximately 5.0-second instrumentation gap explains most of the difference between integrated
sample movement and the reference route length. The canoe identity remained unambiguous before and
after the gap, and the lifecycle events and 0%-to-100% progress were complete.

The action bar exposed a `1.0` canoe speed value during 1,215 samples. No ordinary movement or use
key was down in the sampled client ticks, so these observations should not be used to infer the
server's paddle-input rules without a dedicated input experiment.

## Conclusions

- The validated API/server ID is `canoe` and the observed scored duration is 521100 ms.
- Server lifecycle events and recent/session statistics agree once the API session recovers.
- Canoe progress should follow the composite body or mounted seat position, not search for a
  vanilla `Boat` entity.
- Entity IDs must remain observational; the endpoint item/damage signature and geometry are the
  restart-safe discovery mechanism.
