# ImagineFunUtils 0.0.8 ride ID validation

> Dated validation evidence. Counts and server names are not assumed current; extend this checklist
> only from a newly observed complete ride lifecycle.

Status: complete for the 54 currently rideable, scored rides observed in this campaign. Seasonal
variants, fireworks shows, and historical one-off rides remain separate because they were not
available for an equivalent live lifecycle validation.

## Method

For each ride, the DebugBridge observer records the `riding=true` and
`riding=false` payloads. A ride is considered validated only when all of the
following agree:

1. the observed `rideId` and server `displayName`;
2. the ending `durationMs`;
3. `GET /v1/session/rides` count and time increase;
4. the local IMF `RideCountManager` count; and
5. a matching entry from `GET /v1/session/rides/recent`.

`durationMs` and the ride-stat `time` fields are milliseconds. Recent-ride
timestamps are unzoned America/Los_Angeles local timestamps.

## Validated currently scored ride IDs (54)

| rideId | Server display name | Observed durationMs |
| --- | --- | ---: |
| `grr` | Grizzly River Run | 361017 |
| `alice` | Alice in Wonderland | 206399-206400 |
| `symphonyswings` | Silly Symphony Swings | 87087 |
| `monstersinc` | Monster's Inc. Mike & Sulley to the Rescue! | 229500 |
| `peoplemover` | Peoplemover | 717250 |
| `nemo` | Finding Nemo Submarine Voyage | 790450 |
| `lincoln` | Great Moments with Mr. Lincoln | 371948 |
| `space` | Space Mountain | 180400 |
| `splash` | Splash Mountain | 484000 |
| `tram` | Mickey & Friends Parking Tram | 549986 |
| `mjj` | Mater's Junkyard Jamboree | 89900 |
| `teacups` | Mad Tea Party | 89997-90000 |
| `jcc` | Jessie's Critter Carousel | 120706 |
| `casey` | Casey Jr. Circus Train | 152000 |
| `racers` | Radiator Springs Racers | 268499 |
| `tot` | The Twilight Zone Tower of Terror | 126000 |
| `palaround` | Pixar Pal-A-Round | 550000 |
| `eww` | Inside Out Emotional Whirlwind | 98797 |
| `goldenzephyr` | Golden Zephyr | 107748-107750 |
| `storybook` | Storybook Land Canal Boats | 303750 |
| `rogerrabbit` | Roger Rabbit's Car-Toon Spin | 167999 |
| `ariel` | The Little Mermaid: Ariel's Undersea Adventure | 369000 |
| `monorail` | Disneyland Monorail | 485000 |
| `tikiroom` | Enchanted Tiki Room | 750000 |
| `dumbo` | Dumbo the Flying Elephant | 104699 |
| `indy` | Indiana Jones Adventure | 226499-226500 |
| `buzz` | Buzz Lightyear Astro Blasters | 257499 |
| `hm` | Haunted Mansion | 445150 |
| `matterhorn` | Matterhorn Bobsleds | 145300 |
| `ff` | Flik's Flyers | 98815 |
| `goofy` | Goofy's Sky School | 90599-90600 |
| `incredi` | Incredicoaster | 135000 |
| `jj` | Jumpin' Jellyfish | 44804 |
| `pirates` | Pirates of the Caribbean | 841700 |
| `peterpan` | Peter Pan's Flight | 140000 |
| `heimlich` | Heimlich's Chew Chew Train | 101990 |
| `guardians` | Guardians of the Galaxy: Mission Breakout | 121990 |
| `jc` | Jungle Cruise | 431450 |
| `mainstreetcar` | Main Street Carriages | 381050 |
| `btm` | Big Thunder Mountain Railroad | 201350 |
| `dlrr` | Disneyland Railroad | 1009000 |
| `toads` | Mr Toad's Wild Ride | 108900 |
| `llr` | Luigi's Rollickin' Roadsters | 10908 (anomalous); 94699 |
| `kingarthur` | King Arthur Carrousel | 129701 |
| `autopia` | Autopia | 246067 |
| `pdj` | Pinocchio's Daring Journey | 168750 |
| `pooh` | The Many Adventures of Winnie the Pooh | 199850 |
| `swew` | Snow White's Enchanted Wish | 122001 |
| `astroorbiter` | Astro Orbitor | 87800 |
| `gadgets` | Chip 'N' Dale's Gadget Coaster | 44050 |
| `tomsawyerraft` | Tom Sawyer Island Rafts | 129550 |
| `rotr` | Star Wars: Rise of the Resistance | 293700 |
| `redcartrolley` | Red Car Trolley | good legs: 306997, 307000; bad return-only legs: 300944, 300950 |
| `canoe` | Davy Crockett's Explorer Canoes | 521100 |

## Final special-case captures

### Red Car Trolley

RCT runs two physical cars between the Disney California Adventure front gate (Buena Vista) and
the Guardians of the Galaxy: Mission BREAKOUT! terminus, with brief intermediate stops. Historical
capture data in `/Users/cusgadmin/if-local/rct` models each one-way leg as about 306 seconds, a full
cycle as about 678–680 seconds, and the cars as roughly half a cycle apart. Timing alone cannot
identify which physical car is which.

One car is defective at Buena Vista: it does not automatically unmount the passenger and
immediately returns toward Guardians. The missed unmount also prevents that outbound leg from being
counted. This was initially a user-reported hypothesis and was subsequently confirmed by two live
round trips.

The live predictor at <https://use-ai-for-mc.github.io/rct/> and its matching local checkout at
`/Users/cusgadmin/if-local/rct` (recalibration commit `7692b22`) provide the current prior model: a
679.75-second cycle, two cars half a cycle apart, 306 seconds per directional leg, a 10.3-second
Buena Vista dwell, and an approximately 55.8-second Guardians dwell. Its phase anchor becomes stale
after a server restart, so these values are comparison inputs rather than live truth. This
validation is for the D1/D2 main servers, not DVC.

Capture both directions for both cars. Preserve the global `ride_status` event stream and, for each
logical leg, record wall-clock time, direction, player mount/unmount transitions, nearby trolley-car
entity identity/position, server ride elapsed time, API totals before and after, and recent-ride
entries. Classify a car as good or bad only after observing its Buena Vista terminal behavior. A
missing completion on the defective leg is a result to preserve, not a reason to manufacture an
end event or merge it with the return leg.

For model calibration, sample the player and both detected trolley clusters every client tick and
retain monotonic time, wall-clock time, server/scoreboard identity, entity IDs, centroids, mount
state, and all event timestamps. Derive terminal arrival/departure and intermediate-stop boundaries
from movement transitions. Measure same-car full-cycle period, both one-way travel times, both
terminal dwells, intermediate dwells, and the good/bad phase offset. Keep raw observations so the
fit can report sample count, residuals, and uncertainty instead of replacing the predictor values
with a single manually timed ride.

The D1 good-car round trip was captured on 2026-08-20. Both directions completed and appeared in
`getRecentRides()` at 306997 ms and 307000 ms. Repeated same-route crossings measured the physical
cycle at about 679.74 seconds, closely confirming the predictor's 679.75-second period. See
[`RCT_GOOD_CAR_CAPTURE_2026-08-20.md`](RCT_GOOD_CAR_CAPTURE_2026-08-20.md) for the event timeline,
stop locations, dwell measurements, and entity identity.

The bad car was then observed for two GotG-to-Buena-Vista-to-GotG round trips. It remained mounted
at Buena Vista, emitted no completion or replacement start event there, and only recorded the
return leg after finally unmounting at GotG. The two recent-ride durations were 300944 ms and
300950 ms. Its physical route and stop timings otherwise matched the good car closely. See
[`RCT_BAD_CAR_CAPTURE_2026-08-21.md`](RCT_BAD_CAR_CAPTURE_2026-08-21.md).

### Davy Crockett's Explorer Canoes

The D2 canoe capture emitted a normal `canoe` start/end lifecycle with a server duration of 521100
ms. It also exposed a nonstandard entity layout: each visible canoe is a composite of two
custom-model endpoint armor stands and eight interior armor stands, while the player mounts a
separate seat armor stand. Tracking only an original `Boat` or a fixed entity ID is therefore not
sufficient. The composite-body capture followed the same canoe from 0% to 100% of the 1153.21-block
reference route. See [`CANOE_CAPTURE_2026-08-21.md`](CANOE_CAPTURE_2026-08-21.md).

## Known seasonal variants not currently testable (3)

- `hmh` — Haunted Mansion Holiday
- `gotgmad` — Guardians of the Galaxy: Monsters After Dark
- `hyperspace` — Hyperspace Mountain

## Known fireworks show IDs (8)

These ID/name mappings were supplied on 2026-08-18. Minecraft color and style codes were removed.
They confirm the show names, but do not count as a validated `ride_status` lifecycle.

| showId | Display name |
| --- | --- |
| `fs_hm` | Believe...In Holiday Magic Firework Show |
| `fs_hs` | Halloween Screams Firework Show |
| `fs_dc` | Disney Channel Firework Show |
| `fs_tf` | Together Forever Firework Show |
| `fs_mm` | Mickey's Mix Magic Firework Show |
| `fs_wj` | Wondrous Journeys Firework Show |
| `fs_j4` | July 4th Firework Show |
| `fs_90s` | 90's Nite Firework Show |

## Historical one-off seasonal rides (not tracked)

The statistics menu was observed again on 2026-08-20 and supplied the two previously unknown API
names below. The same menu also showed The Polar Express, but the current 62-entry session API
contained no corresponding ride ID.

| API rideId | Statistics-menu name |
| --- | --- |
| `merrygoround` | The Sugarpine Merry-Go-Round |
| `sugarpineexpress` | The Sugarpine Express |
| unknown / absent from session API | The Polar Express |

These were historical one-off seasonal rides rather than recurring, scored attractions. IMF should
not add product-specific tracking solely for them: an unrecognized ride remains `UNKNOWN`, is not
merged into another ride, and does not contribute to ride counts, goals, or strategy calculations.
