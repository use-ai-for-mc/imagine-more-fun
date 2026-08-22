# Red Car Trolley bad-car capture — 2026-08-21

## Scope and evidence

This capture observed two complete GotG-to-Buena-Vista-to-GotG round trips of the defective Red
Car Trolley on ImagineFun D1. The user identified the car from its terminal behavior: at Buena
Vista it did not unmount the passenger and instead reversed toward GotG. Both repetitions showed
the same behavior.

The raw capture remains at:

`/Users/cusgadmin/Library/Application Support/PrismLauncher/instances/ImagineFun/.minecraft/config/imaginemorefun/rct-captures/rct-live-bad-candidate-1787288625349.ndjson`

Paused-file SHA-256: `df36582de103bd0b83a8e3e1064a69d4bc2595cfa41071d57e5bd592b9624168`.

The file contains 84,435 lines. It remained active for later unrelated rides, so this report filters
the four `redcartrolley` events and the matching marker-cluster windows rather than treating every
row as part of the RCT measurement. The listener reported no callback errors.

## Lifecycle and scoring

| Round trip | GotG start epoch | Internal Buena Vista reset | Recorded return duration | Full physical start-to-end |
| --- | ---: | ---: | ---: | ---: |
| 1 | `1787290442883` ms | `1787290755636` ms | `300944` ms | `613697` ms |
| 2 | `1787291122633` ms | `1787291435386` ms | `300950` ms | `613703` ms |

The payload on each final `riding=false` event exposes a new `startedAtEpochMs` approximately
312.753 seconds after the GotG start. That timestamp coincides with the Buena Vista reversal, but
the server emitted neither a completion nor a new `riding=true` event there. Only the final GotG
arrival emitted `riding=false` and unmounted the player.

`getRecentRides()` consequently recorded only the return legs at 300944 ms and 300950 ms. The
outbound GotG-to-Buena-Vista legs were physically completed but did not increment ride statistics.
This is the concrete failure mode behind the user's report.

The two GotG starts were exactly 679.750 seconds apart, independently matching the calibrated full
cycle period. Do not interpret the roughly 301-second API durations as a faster bad car: they are
return-only scoring windows, while each physical directional leg remains about 307 seconds.

## Physical identity and route comparison

The bad car retained marker cluster `11390/11391/11392`. As with the good car, its mounted seat ID
is not a stable physical-train identity; classify the train from its marker cluster and confirmed
terminal behavior.

Away from the Buena Vista lifecycle boundary, the bad and good cars were materially the same:

- median same-direction spatial difference was about 0.05 block;
- 95th-percentile spatial difference was about 0.20-0.25 block;
- median along-route timing difference was within about 0.04 second;
- 95th-percentile timing difference was at most about 0.17 second; and
- both used the same intermediate stop locations and approximately 10.5-second stop plateaus.

The defect is therefore localized to unmount/lifecycle/scoring behavior at Buena Vista, not a
different route or generally different operating speed.

## Conclusions and limitations

- The good car scores both directions separately and unloads at both terminals.
- The bad car misses the Buena Vista completion, stays mounted, and scores only the return to GotG.
- A full-cycle period of 679.75 seconds is supported by both captures.
- Timing alone is not a safe car classifier after a server restart; observe terminal behavior and
  retain marker-cluster identity.
- The exact good/bad half-cycle phase should be rechecked within one uninterrupted server epoch
  before changing the predictor's half-cycle offset.
