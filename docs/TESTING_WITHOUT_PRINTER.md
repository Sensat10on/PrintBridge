# Testing Without A Printer

Hardware verification is listed in `docs/KNOWN_LIMITATIONS.md`; these are the levels that can be
run without one. Each level proves a narrower property than the one below it.

## Level 1 — unit tests

```powershell
.\gradlew.bat test
```

Covers protocol byte generation (golden SHA-256 fixtures), profile persistence and the profile
form, raster conversion, job queue serialization and cancellation, chunking, IO deadlines, fake
transport fault injection, simulator parsers, and TCP byte-exactness against a loopback server.
This proves deterministic software behaviour only, not printing.

## Level 2 — in-memory fake transport

`ДИАГНОСТИКА` -> `ПРОВЕРИТЬ БЕЗ ПРИНТЕРА` runs the generated job through `FakePrinterTransport` and
reports job state, byte count, chunk count and the captured bytes (also visible as `HEX` and `RAW`).
This proves the queue and transport boundaries without hardware.

## Level 3 — TCP loopback against the virtual printer

1. Start the simulator: `.\gradlew.bat :simulator-app:run --args="127.0.0.1 9100"`
2. In the profile set host `127.0.0.1`, port `9100`, transport `Network`.
3. `ПЕЧАТАТЬ ПО TCP`.

The simulator reports the detected protocol, byte count, commands, warnings and a preview, which
proves TCP byte preservation plus parser compatibility.

## Level 4 — Windows bridge

Forward the same bytes into a real Windows print queue; see `docs/WINDOWS_PRINT_BRIDGE.md`. This
adds a real spooler and a real printer driver to the path, and is the closest approximation of a
physical printer that does not require thermal hardware.

## Level 5 — Bluetooth virtual printer

`BluetoothVirtualPrinterServer` (diagnostics tab) turns one Android device into an SPP endpoint.
A second device prints to it over Bluetooth SPP and the receiving device shows the parsed preview.
This exercises the real Bluetooth stack; it is still **not** a substitute for a Bluetooth thermal
printer and remains unverified on physical devices.
