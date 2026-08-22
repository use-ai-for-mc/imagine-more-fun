# Red Car Trolley good-car capture — 2026-08-20

## Scope and evidence

This capture observed one complete cycle of the same physical Red Car Trolley on the ImagineFun
D1 server (`disneyland1`, dimension `minecraft:dlnew`): GotG to Buena Vista, the Buena Vista
turnaround, Buena Vista to GotG, and the following GotG departure. The user confirmed that this car
automatically unmounted them at Buena Vista, so this report calls it `car-A / good`.

The raw capture remains at:

`/Users/cusgadmin/Library/Application Support/PrismLauncher/instances/ImagineFun/.minecraft/config/imaginemorefun/rct-captures/rct-live-gotg-1787223172888.ndjson`

Paused-file SHA-256: `22fec44de52c4e3716d52702549f5c3f4fbe399a6752c33adb13a995d751c4bc`.

The recorder was paused, not removed, after the second arrival at GotG. It contains 19,774 20 Hz
samples and four global `ride_status` events, with no listener errors. The median sample interval is
50 ms and the 95th percentile is 69 ms.

The comparison model is the live predictor at <https://use-ai-for-mc.github.io/rct/> and its
matching local checkout at `/Users/cusgadmin/if-local/rct`, commit `db8595f`.

## Server and API lifecycle

| Boundary | Server time / duration |
| --- | ---: |
| GotG to Buena Vista start | epoch `1787223484381` ms |
| GotG to Buena Vista completion | `306997` ms |
| Buena Vista to GotG start | epoch `1787223801379` ms |
| Completion-to-next-start gap at Buena Vista | `10001` ms |
| Buena Vista to GotG completion | `307000` ms |

`getRecentRides()` returned both completed legs as separate `redcartrolley` entries with exactly
those durations. The good car therefore counts both directions normally.

The first completion was followed by an observed mounted-to-unmounted transition about 245 ms
after the payload-derived completion time. The player remounted about 1.001 seconds later. This
corroborates the reported automatic-unmount behavior independently of the user's classification.

## Physical train identity

The good train retained the same three custom-model armor-stand markers through both directions:

- body markers (iron shovel damage 29): entity IDs `11401` and `11403`
- pole marker (iron shovel damage 31): entity ID `11402`

The player's seat/vehicle entity changed from `9128797` on the first leg to `9428682` on the
second. Train identity must therefore follow the marker cluster, not the mounted vehicle ID.

A second marker cluster (`11390`, `11391`, and `11392`) was partially visible in the capture. It is
retained as unclassified `car-B`; the available windows do not include its terminal behavior and
are not sufficient to call it bad or to revise the two-car phase offset.

## Comparison with the predictor

| Quantity | Predictor prior | This capture | Interpretation |
| --- | ---: | ---: | --- |
| Same-car full cycle | `679.75 s` | mean `679.743 s`, median `679.740 s` | Confirmed extremely closely |
| Server/API leg duration | route model `306 s` | `306.997 s`, `307.000 s` | Scored ride time is effectively `307 s` |
| Physical motion between stationary boundaries | about `306 s` | about `306.2 s`, `306.4 s` | Consistent within client interpolation uncertainty |
| Buena Vista completion-to-start gap | route dwell `10.3 s` | `10.001 s` | Server lifecycle uses about `10.0 s` |
| Buena Vista rendered stationary plateau | route dwell `10.3 s` | about `10.25 s` | Confirms the physical predictor value |
| GotG rendered stationary plateau | cycle-dependent | about `56.9–57.1 s` | Close to the current model; retain as approximate |
| Intermediate rendered stops | roughly 10-second plateaus | `10.35–10.55 s` | Four observed stops confirm both directional profiles |

The full-cycle estimate uses seven same-direction crossings of the same projected route positions
on consecutive GotG departures. Individual estimates range from `679.713 s` to `679.796 s`; this
is stronger evidence than a single manually marked departure.

The distinction between server lifecycle time and rendered motion matters. `ride_status` provides
the precise scored duration, while entity positions include client interpolation around stopping
and starting. The predictor should not use the `307 s` API duration as a blind replacement for its
`306 s` movement curve.

## Route-shape validation

Projecting the live marker cluster onto the predictor polyline reproduced the two modeled
intermediate stops in both directions:

| Direction | Predictor projected positions | Observed projected positions |
| --- | --- | --- |
| GotG to Buena Vista | `246.21`, `531.80` blocks | `246.51`, `531.99` blocks |
| Buena Vista to GotG | `530.12`, `246.58` blocks | `529.92`, `246.53` blocks |

All four discrepancies are at most about 0.31 projected block. The reverse direction follows a
directionally offset physical path in places, so a single schematic polyline can have several
blocks of lateral residual even when its progress and stop locations are correct. The separate
`sFwd` and `sRev` profiles in the existing model are therefore justified.

## Conclusions and remaining work

The capture supports the user's description and the existing predictor:

- the trolley performs two directional legs with two intermediate stops per leg;
- the same physical good car automatically unloads at each terminal;
- each completed direction produces its own API ride entry;
- the 679.75-second wall-clock cycle is accurate to within the capture's few-hundredths-of-a-second
  crossing spread;
- the movement curve and projected stop positions are already strong, while server ride duration
  should be documented separately as approximately 307 seconds.

This file does not validate the bad-car missed completion, the bad car's Buena Vista reversal, or
the exact two-car phase offset. Resume the retained listener and capture `car-B` through Buena Vista
before changing those parts of the model.

## Follow-up — 2026-08-21

The requested bad-car follow-up was completed after this report. Two round trips confirmed the
missed Buena Vista unmount and missed outbound scoring while showing no material route or speed
difference elsewhere. See [`RCT_BAD_CAR_CAPTURE_2026-08-21.md`](RCT_BAD_CAR_CAPTURE_2026-08-21.md).
