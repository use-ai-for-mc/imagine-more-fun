# Quest collectible guidance

NRA automatically highlights identified ImagineFun quest props while a server quest boss bar
displays a distance and NRA's global toggle is enabled. The locally generated PIM Pin Trader bar does not
activate this feature. There is no separate toggle or command.

The title must start with `Quest: ` and end in a parenthesized nonnegative integer or decimal
distance, optionally followed by a direction arrow. The parenthesized distance must also have
effective text color `#C8D6E5`; plain unstyled text does not qualify. Live MCP inspection on 2026-09-04 returned
`Quest: Find 5 Honey Pots (1357.5) ⬅`: `5` is the requested count, and `1357.5` is the distance.
A subsequent live reading, `Quest: Find 5 Honey Pots (20.9) ⬇`, had prefix color `#FC5371`,
body color `#32FF7E`, distance color `#C8D6E5`, and a white arrow. Styled component traversal
resolves inherited colors and accepts distances split across multiple components.
Quest titles with only counts, `(2/5)` progress, or no distance do not enable highlighting.
The current title is checked for every activation decision, so a name update removing the
distance disables highlights even if the same boss-bar UUID remains present.

`QuestCollectibleGlow` restores the detection from `b87f0ee` (removed in `825f107`). A target must
combine all three signals:

- An invisible armor stand with a nonempty head equipment slot.
- An `Interaction` whose origin is within 0.35 blocks of the stand's origin. The Interaction hitbox
  is ignored: live Honey Pots used a 1.3×2 box that also overlapped a nearby trash-can stand.
- Pure-white, unit-scale `minecraft:dust` within 2.25 blocks of the estimated head position,
  1.5 blocks above the stand's base. Nearby qualifying stands may all glow. The through-wall beam
  is independent of those stands: there is one beam, originating at the centroid of currently
  rendered matching dust, and it disappears within 20 ticks after that dust stops. Stands without
  recent dust are also dropped so leftover outlines do not linger.

Identified props receive a client-only glowing outline around their head model; the invisible
wooden stand remains hidden. Entity render-distance culling is bypassed for these targets. The
beam extends from the dust centroid to world Y=320 within a 300-block horizontal camera radius
and alternates red and blue every 250 milliseconds.

Both server particle packets and concrete particle creation feed detection. The packet hook runs
after Minecraft's client-thread handoff, so it can safely inspect entities even if distance or
particle settings suppressed particle creation. Tracking and render-state collection stay on the
client thread. NRA registers the renderer, prunes targets every tick, and resets on disconnect.
Removed entities and entities from an old world are excluded from rendering immediately. Losing
the distance-bearing quest boss bar (including a title update removing its distance) or disabling NRA hides highlights immediately and clears tracking on the next
tick. While that same distance-bearing quest bar is present and matching collectible dust is actually
spawned for rendering, `ImfQuestNightMixin` makes `ClientClockManager.getTotalTicks()` report
midnight so the sky is night. Packet-only dust observations do not count. The night gate drops
within 20 ticks after those particles stop spawning. It does not write the client clock, and
`DayTimeHandler` skips its fullbright noon reset for the duration so a daytime sky cannot come
back. This restoration does not restore the old dust debug recorder.

JUnit covers dust color/scale matching, the historical trophy sample, head-radius boundaries, dust
centroid averaging for the single beam, Interaction origin pairing, and the observed distance format and
color, inherited/split styles, rejection of counts/progress without distance, and exclusion of PIM's local bar. Compilation checks the Minecraft 26.2 API adaptation.
Live ImagineFun validation still needs a fully restarted client with the built artifact: check a
quest prop's outline and red/blue beam, visibility through walls and with minimal particles, and
cleanup on collecting it, ending the event, disconnecting, or disabling NRA. Build success does
not prove these rendering and server-data paths.
