# Simulator

PrintBridge Simulator is a first-class virtual printer. It conservatively detects `ESC_POS`,
`TSPL`, `GOOJPRT_LABEL` or `UNKNOWN`, records the evidence for the decision, parses the command
subsets that PrintBridge can generate, and builds a virtual receipt or label preview.

## Usage

Standalone TCP process (defaults to `127.0.0.1:9100`):

```powershell
.\gradlew.bat :simulator-app:run --args="127.0.0.1 9100"
```

Bind to `0.0.0.0` only when LAN testing is intentional. Every accepted connection prints the
detected protocol, received byte count, command count, warning count and the first preview lines.

In the app, the `ДИАГНОСТИКА` tab shows the same information for generated jobs: `ПРОСМОТР` for the
parsed preview, `КОМАНДЫ` for the command list, `HEX` and `RAW` for the exact bytes.

## Detection order

1. TSPL: ASCII evidence such as `SIZE` + `CLS` + `PRINT` (HIGH confidence)
2. ESC/POS: `ESC @`, `GS ( k` (QR) or `GS v 0` (raster) — HIGH with several markers, MEDIUM with one
3. GOOJPRT label: `1A 5B 01`, `1A 4F 01`, `1A 54 01` — HIGH with several markers, MEDIUM with one
4. otherwise `UNKNOWN` with LOW confidence and the raw bytes preserved

## Parsers

- `EscPosParser` — text, LF, initialize, align, bold, feed, scale, cut, `GS v 0` raster, `GS ( k` QR.
  Renders packed raster rows as `#`/`.` art and extracts the first raster for the preview model.
- `TsplParser` — `SIZE`, `GAP`, `CLS`, `DENSITY`, `SPEED`, `TEXT`, `BARCODE`, `QRCODE`, `BOX`,
  and `BITMAP` with its binary payload. Unknown commands and malformed `BITMAP` lines are reported
  as warnings instead of aborting.
- `GoojprtLabelParser` — page begin/end/print, text, line, box, rect, barcode, QR and bitmap, with
  truncation and unknown-command warnings.

All parsers are total: truncated or unknown input produces warnings, never an exception, and the
original bytes are always available in `SimulatorJob.rawBytes`.

## Virtual printer over Bluetooth

`BluetoothVirtualPrinterServer` in `printer-bluetooth` accepts SPP clients and routes their payload
into the same simulator, which lets two Android devices exercise the full Bluetooth path without a
physical printer.
