# Space Mountain and Hyperspace Mountain enhancements

## Activation contract

`SpaceMountainOverride.isActive()` is the product gate. Enhancements activate only when:

- the SmoothCoasters mod is loaded at runtime;
- the `spaceMountainEnhancements` config toggle is enabled;
- NRA's ImagineFun server gate is true; and
- `CurrentRideHolder` reports Space Mountain or Hyperspace Mountain.

## Registered subsystems

`ImfClient` registers the following before NRA initialization:

1. `SpaceMountainStarRenderer`
2. `SpaceMountainTrackRenderer`
3. `SpaceMountainBlockOverride`
4. `SpaceMountainTunnelRenderer`
5. `SpaceMountainEntryTunnelSeal`
6. `SpaceMountainDiscoBall`
7. `SpaceMountainRideAudio`

`SpaceMountainEntityHider` is queried from its rendering mixin. `CoasterTiltAmplifier` is driven by
the optional SmoothCoasters mixin and has no entrypoint registration.

## Responsibilities

- `SpaceMountainBlockOverride`: applies the baked IFOV dome block-state overlay, records original
  states, re-meshes on activation, and restores after the delayed deseal window.
- `SpaceMountainDiscoBall`: projects moving star dots by raycasting world blocks and filtering hits
  through the baked dome shell/exclusion data.
- `SpaceMountainStarRenderer`: renders a static baked-wall star layer.
- `SpaceMountainTunnelRenderer` and `SpaceMountainEntryTunnelSeal`: render and seal the launch
  tunnel sequence.
- `SpaceMountainTrackRenderer`: renders baked rail/spine/strut geometry. Banking comes from the roll
  column in `dome_track.bin`.
- `SpaceMountainRideAudio`: owns independent wind and rail-friction loops tied to ride state.
- `SpaceMountainEntityHider`: hides known show-prop armor stands while the gate is active.

## Camera tilt is independent of baked track roll

`CoasterTiltAmplifier` scales the live roll supplied by SmoothCoasters using
`coasterTiltMultiplier`. It applies to all SmoothCoasters-covered rides on ImagineFun, not only
Space/Hyperspace Mountain. It does not read `dome_track.bin` and must not be combined with a second
position-sampled camera-bank implementation; that caused double tilt in the abandoned experiment.

The baked roll in `dome_track.bin` is consumed only by the Space Mountain track renderer's geometry.

## Bundled and override data

| Resource | Current consumer |
|---|---|
| `imaginemorefun/dome_overlay.bin` | `SpaceMountainBlockOverride` |
| `imaginemorefun/dome_borders.bin` | Static stars and disco-ball shell filtering |
| `imaginemorefun/dome_track.bin` | `CoasterTrackData` / `SpaceMountainTrackRenderer` |
| `imaginemorefun/disco_balls.json` | Default disco-ball projector configuration |
| `imaginemorefun/disco_exclusion.json` | Default excluded projection cells |
| `assets/imaginemorefun/textures/particle/star.png` | Star renderers |
| `assets/imaginemorefun/textures/particle/track.png` | Track/tunnel rendering |

Several data loaders prefer files under `<configDir>/imaginemorefun/` and fall back to bundled
resources. Treat config-dir data as a local override, not something to silently copy into source.

Historical implementation and design notes live under `docs/archive/`. They preserve deleted bake
tools and experiments and are not current instructions.
