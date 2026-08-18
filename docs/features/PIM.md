# PIM

## Current surface

PIM is enabled only on an ImagineFun server and exposes one client command:

```text
/pim
```

The command opens `PimScreen`. Valuation, collection display, export, reset, trade actions, and FMV
controls are screens/actions inside that GUI; there are no separate `/pim reset`, `/pim export`, or
other subcommands in the current command tree.

## Main components

- `pim/ui/PimScreen.java`: combined screen and user actions.
- `pim/screen/PinBookHandler.java`: series collection progress.
- `pim/screen/PinDetailHandler.java`: per-pin condition and rarity details.
- `pim/screen/PinRarityHandler.java`: series availability, colors, pack tiers.
- `pim/screen/PimConfigHandler.java`: FMV discount preference.
- `pim/pin/PinCalculationUtils.java`: valuation/DP calculations.
- `pim/trader/PinTrader.java`: trader interaction and warp workflow.
- `pim/pinpack/PinPackOverlayRenderer.java`: pack color overlay.
- `pim/hoarder/*`: guarded trade-confirmation helper.

## Persistence

PIM deliberately retains its compatibility filenames at the Fabric config root:

- `pim_config.json`
- `pim_pin_book.json`
- `pim_pin_detail.json`
- `pim_pin_rarity.json`

All paths are provided by `ImfStorage`; do not construct relative `config/...` paths. The legacy
`pim_fmv.json` file is no longer read or written and is deleted only when the user resets pin data.

## Boundaries for future work

- Server gating is owned by `PimClient`, independently from NRA's `globalEnable`.
- Observed pin item schemas are research evidence, not stable server API. See
  [`../research/PIN_ITEM_DATA.md`](../research/PIN_ITEM_DATA.md) and revalidate before parser changes.
- Do not turn exploratory programmatic-unboxing notes into automation without explicit scope and
  an in-game safety review.
- Keep collection writes atomic through `ImfFileIO`.
