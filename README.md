# PrintBridge

PrintBridge is a Kotlin/Android printing bridge for thermal and label printers. It generates
ESC/POS, TSPL and GOOJPRT/MTP byte streams, sends them over fake, TCP/IP, Bluetooth SPP or USB
transports, and can parse its own output back into a text/raster preview.

## Status

Local/intermediate release. Software behaviour is covered by unit tests; printing against
physical printers and installation on physical devices is **not** verified yet. See
`docs/KNOWN_LIMITATIONS.md` for the exact scope and `docs/RELEASE_PROCESS.md` for how a release is
built, signed and verified.

## Modules

| Module | Responsibility |
|---|---|
| `print-core` | Domain models, job queue, chunking, IO deadline helper, raster primitives, errors |
| `printer-drivers` | Deterministic ESC/POS, TSPL and GOOJPRT byte generation |
| `printer-transport` | Fake and raw TCP transports, local network discovery |
| `printer-bluetooth` | Bluetooth Classic SPP transport, bonded-device listing, virtual printer server |
| `printer-usb` | USB host transport: device enumeration, endpoint selection, bulk writes |
| `simulator-core` | Protocol detection, command parsers, virtual preview model |
| `simulator-app` | Standalone TCP virtual printer (JVM) |
| `windows-print-bridge` | Raw TCP -> Windows print queue bridge (JVM/Windows) |
| `app` | Android application (Jetpack Compose) |

Dependency direction is strictly one-way: `app` -> transports/drivers/simulator -> `print-core`.
`print-core` depends on nothing but coroutines.

## Requirements

- JDK 21. The build pins this toolchain and Gradle downloads it automatically
  (see `docs/RELEASE_PROCESS.md`); do not build with a newer JDK.
- Android SDK with platform 36 for the Android modules (`local.properties` -> `sdk.dir`).
- Windows, macOS or Linux for the JVM modules; Windows for `windows-print-bridge`.

## Build and test

Use the wrapper — do not call a Gradle installation by absolute path.

```powershell
# Unit tests for every module
.\gradlew.bat test

# Debug APK
.\gradlew.bat assembleDebug

# Signed release APK (needs signing credentials, see docs/RELEASE_PROCESS.md)
.\gradlew.bat :app:assembleRelease "-Pprintbridge.requireReleaseSigning=true"

# Android lint
.\gradlew.bat lintDebug
```

Install the debug build:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Trying it without a printer

1. Start the TCP virtual printer:

   ```powershell
   .\gradlew.bat :simulator-app:run --args="127.0.0.1 9100"
   ```

2. Open PrintBridge, pick a profile, choose a job template and tap `СФОРМИРОВАТЬ ЗАДАНИЕ`.
3. Diagnostics tab -> `ПРОВЕРИТЬ БЕЗ ПРИНТЕРА` sends the bytes to the in-memory fake transport.
4. For TCP: set the profile host to `127.0.0.1`, port `9100`, transport `Network`, then
   `ПЕЧАТАТЬ ПО TCP`. The simulator prints the detected protocol, command count and a preview.

The Windows bridge works the same way with port `9191`; see `docs/WINDOWS_PRINT_BRIDGE.md`.

## Printing your own files

The `ПЕЧАТЬ ФАЙЛА` section prints an image, a PDF or a text/CSV file through whichever transport the
profile selects. Images and PDF pages are reduced to a 1-bit raster at the printer width; text is
wrapped to the printable column count.

The app is free until a license is granted, and the free version stamps a watermark on **every**
print. Google Play Billing is not connected yet — the diagnostics section has development buttons
that grant and revoke the local entitlement so both paths can be checked. See
`docs/KNOWN_LIMITATIONS.md` for the exact scope.

## Documentation

- `docs/ARCHITECTURE.md` — module boundaries and data flow
- `docs/RELEASE_PROCESS.md` — toolchain, versioning, signing, verification
- `docs/KNOWN_LIMITATIONS.md` — what is deliberately unverified
- `docs/PRINTBRIDGE_RELEASE_VERIFICATION_REPORT_0_2_0.md` — latest verification run
- `docs/PRINTER_PROFILES.md`, `docs/TCP_PRINTING.md`, `docs/BLUETOOTH_SPP.md`, `docs/SIMULATOR.md`,
  `docs/TESTING_WITHOUT_PRINTER.md`, `docs/WINDOWS_PRINT_BRIDGE.md`,
  `docs/MILESTONE3_GOOJPRT_PREP.md`, `docs/ADDING_A_DRIVER.md`, `docs/ADDING_A_TRANSPORT.md`
