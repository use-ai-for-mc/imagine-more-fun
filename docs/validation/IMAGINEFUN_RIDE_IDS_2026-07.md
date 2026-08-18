# ImagineFunUtils 0.0.8 ride ID validation

> Dated validation evidence. Counts and server names are not assumed current; extend this checklist
> only from a newly observed complete ride lifecycle.

Status: in progress. This is the durable checklist for the live `ride_status`
validation session started on 2026-07-27.

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

## Validated IDs (52 / 62)

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

## Pending known rides (47)

### Disneyland

- `canoe` — Davy Crockett's Explorer Canoes

### Disney California Adventure

- `redcartrolley` — Red Car Trolley

### Retro and seasonal

- `hmh` — Haunted Mansion Holiday
- `gotgmad` — Guardians of the Galaxy: Monsters After Dark
- `hyperspace` — Hyperspace Mountain

## API-only IDs pending a server display name (5)

- `fs_hm`
- `fs_hs`
- `fs_mm`
- `merrygoround`
- `sugarpineexpress`

Do not assign product-facing names to these IDs until a live `ride_status`
payload or server documentation supplies the display name.
