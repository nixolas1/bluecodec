# BlueCodec Android design

User-directed reference: My Audiobooks' warm Material 3 theme. This is a small native utility, optimized for choosing a sound preference and then leaving it alone.

## Tokens

| Role | Light | Dark |
| --- | --- | --- |
| Page | `#F6EBDD` | `#241C17` |
| Section surface | `#FFF8EF` | `#30261E` |
| Text | `#38281F` | `#F2E4D2` |
| Secondary text | `#715B4C` | `#C8AF98` |
| Primary action | `#9A4C1F` | `#F1B278` |
| On primary | `#FFF8EF` | `#40220F` |
| Tonal control | `#ECDCC9` | `#46372B` |

Native sans typography: app title 28sp, device title 22sp, section title 20sp, body 15–16sp, secondary 14sp. Device titles wrap. System font scaling remains enabled. Insets follow status/navigation bars.

Sections use MaterialCardView with 28dp corners and no elevation/stroke. Primary controls use MaterialButton, codec choices use single-selection ChipGroup, and automatic behavior uses MaterialSwitch. Tap targets are at least 48dp. Secondary flows use warm Material bottom sheets.

Main hierarchy: identity → selected device/current sound → saved codec choices → automatic behavior → help/pause. Errors expose useful recovery actions; raw API diagnostics stay in help. The connected-device section and preference section always distinguish current vs saved values.

Launcher: authored vector intact heart with a separate cream Bluetooth badge (selected concept B), adaptive icon plus monochrome support. Device avatars use SHA-256 of the stable device ID to choose pastel hue, headband geometry, cushion shape and details. No network or raster assets are required. Renames do not change avatars.
