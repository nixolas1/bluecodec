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

Main hierarchy: identity → connected-device carousel with codec choices → sound output radio list → reconnect behavior → help/pause. Swiping configures a device independently of the playback route. Use compact 48dp avatars with pale colors, a thin divider before codec chips, and visible, closely spaced page dots without a numeric count. Show codec help only after a choice changes and hide Apply on reconnect for single-codec devices. With no connected audio devices, show the last connected device and Connect. Sound output has a speaker title icon, 48dp radio rows, and a trailing spinner while switching; only verified routes become checked. Errors expose useful recovery actions; raw API diagnostics stay in help.

Use a small “Set default codec” label beneath the device-card divider. Avoid empty
status spacing below Apply on reconnect. Sound output has 10dp between its title
and choices. Block automatic reconnect uses one tile-specific explanation and
an Add Bluetooth tile action; experimental compatibility details live in Help.
The footer action reads “Pause automatic codec switching.” A “Report an issue”
link opens the GitHub issue chooser without collecting or submitting diagnostics.

Launcher: authored vector intact heart with a separate cream Bluetooth badge (selected concept B), adaptive icon plus monochrome support. Device avatars use SHA-256 of the stable device ID to choose pastel hue, headband geometry, cushion shape and details. Android's user-assigned Speaker audio category uses a speaker glyph instead. No network or raster assets are required. Renames do not change avatars.
