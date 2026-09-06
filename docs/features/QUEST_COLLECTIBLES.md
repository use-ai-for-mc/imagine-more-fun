# Quest collectible guidance

NRA automatically highlights identified ImagineFun quest props while a server quest boss bar
displays a distance and NRA's global toggle is enabled. The locally generated PIM Pin Trader bar does not
activate this feature. There is no separate toggle or command.

The title must start with `Quest: ` and end in a parenthesized nonnegative integer or decimal
distance, optionally followed by a direction arrow. The arrow uses the same eight symbols PIM
writes on its local pin-trader bar, clockwise from the player's forward: `⬆↗➡↘⬇↙⬅↖`.
While that distance bar is present and its reported distance is at most 300 blocks (inclusive),
`QuestEdgeGlowRenderer` draws a purple (`#8A00FF`, matching the autograb overlay) crescent: a large circle minus an
oppositely offset smaller circle, clipped to the screen. Both circles use actual GUI-pixel radii;
the outer circle extends beyond the screen corners. Along the target bearing the crescent reaches
inward by roughly one quarter of the screen dimension. The inner arc fades over the innermost 35%
of that depth, retaining a broad saturated region. There are no rectangular layout anchors or
radial spot textures. Non-overlapping column spans follow the analytic circle boundaries. Three
low-contrast light-purple range arcs are clipped into the crescent to add a subtle radar-screen texture.
Peak opacity breathes between 45% and 75% over four seconds. The crescent disappears above 300
blocks, when the distance bar disappears, or when no valid beam targets remain. This range limit
applies only to the HUD; entity outlines, beams, and the night-sky gate retain their existing rules.
The HUD computes a continuous 360-degree bearing every rendered frame from the current camera position/yaw
and the nearest horizontal beam origin (tracked armor stand or loaded NPC marker). It does not use the
server arrow or fall back to it when targets disappear. The inner circle offset rotates continuously with the bearing, without snapping
to sectors or waiting for server arrow updates. Boss-bar distance still controls the 300-block
limit. A target directly above/below the camera maps to forward; equal-distance targets use a stable
coordinate tie-break. Switching worlds or
disconnecting resets the pulse cycle. The parenthesized distance must also have
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
  is independent of dust coordinates: there is one beam, anchored two blocks above the nearest
  currently tracked armor stand, and it disappears within 20 ticks after matching dust stops. This
  prevents server-side dust movement from shaking the beam and HUD bearing. Stands without
  recent dust are also dropped so leftover outlines do not linger.

Identified props receive a client-only glowing outline around their head model; the invisible
wooden stand remains hidden. Entity render-distance culling is bypassed for these targets. The
beam extends from the selected entity anchor to world Y=320 within a 300-block horizontal camera radius
and alternates red and blue every 250 milliseconds.

Talk-to-NPC quests use a different construction. Live MCP inspection on 2026-09-05 of Yoda during
`Quest: Meditate with Yoda (4.7) ⬆` found a no-gravity `minecraft:iron_pickaxe` item entity named
`Brickhead` at damage 125 (the yellow exclamation-mark model) at the same X/Z as an unnamed
`RemotePlayer`, 2.75 blocks above the NPC's feet, with `Yoda` and `[Right click]` text displays
just below the mark. While the same distance-bearing quest bar is present, each matching marker
gets its own through-wall red/blue beam at the item position, and the paired `RemotePlayer`
is marked for vanilla `shouldEntityAppearGlowing` (the same client-only outline collectibles use). Markers are rescanned each tick
within 96 blocks and drop immediately when the item unloads, the NPC pairing is lost, or the
distance bar disappears. Gravity-affected dropped items and other Brickhead damage values do not
match.

Both server particle packets and concrete particle creation feed detection. The packet hook runs
after Minecraft's client-thread handoff, so it can safely inspect entities even if distance or
particle settings suppressed particle creation. Tracking and render-state collection stay on the
client thread. NRA registers the renderer, prunes targets every tick, and resets on disconnect.
Removed entities and entities from an old world are excluded from rendering immediately. Losing
the distance-bearing quest boss bar (including a title update removing its distance) or disabling NRA hides highlights immediately and clears tracking on the next
tick. While that same distance-bearing quest bar is present and matching collectible dust is actually
spawned for rendering or a matching NPC exclamation-mark is loaded, `ImfQuestNightMixin` makes `ClientClockManager.getTotalTicks()` report
midnight so the sky is night. Packet-only dust observations do not count. The night gate drops
within 20 ticks after those particles stop spawning, or immediately when the last NPC marker unloads. It does not write the client clock, and
`DayTimeHandler` skips its fullbright noon reset for the duration so a daytime sky cannot come
back. This restoration does not restore the old dust debug recorder.

JUnit covers dust color/scale matching, the historical trophy sample, head-radius boundaries,
stable nearest-entity selection for the single beam, Interaction origin pairing, the observed Yoda exclamation-mark
item fingerprint, NPC pairing height, PIM's eight
boss-bar direction symbols and HUD edge anchors, and the observed distance format and
color, inherited/split styles, rejection of counts/progress without distance, and exclusion of PIM's local bar. Compilation checks the Minecraft 26.2 API adaptation.
Live ImagineFun validation still needs a fully restarted client with the built artifact: check a
quest prop's outline and red/blue beam, an NPC exclamation-mark beam, visibility through walls and with minimal particles, and
cleanup on collecting it, ending the event, disconnecting, or disabling NRA. Build success does
not prove these rendering and server-data paths.
