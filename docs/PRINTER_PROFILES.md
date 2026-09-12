# Printer Profiles

A profile describes how to reach one printer and how to format a job for it. Profiles are stored
as JSON in `SharedPreferences` and merged over the built-in defaults, so new fields keep working
after an application upgrade.

## Fields

| Field | Meaning |
|---|---|
| `id`, `displayName` | Identity; `displayName` is shown in the UI |
| `manufacturer`, `model`, `alternativeNames`, `bluetoothNamePatterns` | Vendor metadata |
| `protocol` | `ESC_POS`, `TSPL`, `GOOJPRT_LABEL`, `UNKNOWN` (ZPL/CPCL/EPL have no driver) |
| `dpi`, `paperWidthMm`, `paperHeightMm`, `gapMm` | Geometry; `paperHeightMm` is kept only for label protocols |
| `transportType` | `FAKE`, `TCP`, `BLUETOOTH_SPP`, `USB` (`BLUETOOTH_BLE` is declared but not implemented) |
| `chunkSize`, `delayBetweenChunksMs` | Write pacing; `chunkSize` is limited to 64 KiB |
| `writeTimeoutMs` | Per-write bound for USB and Bluetooth (100..120000 ms) |
| `networkHost`, `networkPort`, `networkConnectTimeoutMs`, `networkWriteTimeoutMs` | TCP target and its timeouts |
| `bluetoothDeviceName`, `bluetoothDeviceAddress` | Bound SPP device |
| `usbVid`, `usbPid` | Bound USB device |
| `supportsCut`, `supportsStatus` | Capability flags used when generating jobs |
| `knownQuirks`, `verified`, `notes` | Provenance; `verified = false` means "not confirmed on hardware" |

## Built-in profiles

`DefaultProfiles.all` ships five entries:

| id | Protocol | Paper |
|---|---|---|
| `generic-escpos-58-203` | ESC/POS | 58 mm, 203 DPI |
| `generic-escpos-80-203` | ESC/POS | 80 mm, 203 DPI |
| `generic-tspl-label-203` | TSPL | 100 x 150 mm label, 203 DPI |
| `generic-tspl-label-300` | TSPL | 100 x 150 mm label, 300 DPI |
| `goojprt-mtp-label-58-203` | GOOJPRT/MTP label | 58 x 40 mm label, 203 DPI, `verified = false` |

Generic profiles avoid model-specific compatibility claims. The GOOJPRT entry is ported from the
vendor SDK and stays unverified until it has been tested on a physical MTP-2/MTP-II printer.

## Editing

The `ПРОФИЛИ` tab edits the active profile, creates and duplicates profiles, and switches protocol
presets (ESC/POS, TSPL, GOOJPRT). `profileFromEditor()` validates every field and returns `null`
for out-of-range input, which the UI reports instead of saving a broken profile:

- DPI 1..2400, paper width 0.1..1000 mm, port 1..65535
- chunk size 1..65536 bytes, inter-chunk delay 0..60000 ms
- timeouts 100..120000 ms

Values are persisted only when `СОХРАНИТЬ` is tapped. Selecting another profile or protocol resets
the form from that profile, so the editor cannot show stale values.
