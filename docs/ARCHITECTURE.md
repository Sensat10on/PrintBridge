# Architecture

## Data flow

```text
job template -> Print Core (models, queue, chunking) -> Printer Driver (bytes)
             -> Printer Transport (fake | TCP | Bluetooth SPP | USB) -> Printer
                                        |
                                        +-> Simulator Core (detect, parse, preview)
```

## Module boundaries

| Module | Owns | Must not |
|---|---|---|
| `print-core` | Domain models, `PrintJob`/`PrintQueue`, chunking, `withIoDeadline`, raster primitives, error types | Know about Android, sockets or protocols |
| `printer-drivers` | Deterministic byte generation for ESC/POS, TSPL, GOOJPRT | Open sockets, discover devices, request permissions, depend on UI |
| `printer-transport` | Fake and raw TCP I/O, local network probing | Generate protocol commands |
| `printer-bluetooth` | Bluetooth Classic SPP transport, bonded-device listing, virtual printer server | Generate protocol commands |
| `printer-usb` | USB host transport: enumeration, endpoint selection, bulk writes | Generate protocol commands |
| `simulator-core` | Protocol detection, command parsers, virtual preview model | Perform I/O |
| `simulator-app` | TCP virtual printer process | Contain protocol logic (delegates to `simulator-core`) |
| `windows-print-bridge` | Raw TCP listener that forwards bytes into a Windows print queue | Convert or interpret printer data |
| `app` | Compose UI and Android glue: profile storage, permission flows, transport selection | Contain protocol or transport logic |

Dependency direction is one-way: `app` -> transports/drivers/simulator -> `print-core`.
`print-core` depends only on `kotlinx-coroutines-core`; there are no cycles in the project graph.

## Key contracts

- `PrinterTransport` — `state`, `connect()`, `disconnect()`, `write(ByteArray)`. Transports report
  failures as `PrintBridgeError` subclasses, never as raw exceptions.
- `PrinterDriver` — `protocol`, `testPage(profile): ByteArray`. Drivers are pure and deterministic;
  golden SHA-256 fixtures in `printer-drivers` pin their output byte for byte.
- `PrintQueue` — serializes jobs with a mutex, maps transport failures to `FAILED`/`CANCELLED` job
  states, and always disconnects. A transport that reported `ERROR` keeps that state after
  `disconnect()`, so callers can distinguish "failed" from "cleanly closed".
- `WriteChunker` — splits the payload into profile-sized chunks clamped to
  `WriteChunker.MAX_CHUNK_SIZE` (64 KiB), so a corrupted profile cannot overflow the loop.
- `withIoDeadline` (`print-core`) — runs blocking JVM IO on a daemon thread under a deadline.
  Blocking IO ignores coroutine cancellation, so the caller passes an `onTimeout` action that
  closes the socket; this is how Bluetooth and USB writes are bounded.

## Android application

`app` is a single-activity Compose UI (`MainActivity.kt`) plus `ProfileStore`:

- `ProfileStore` persists profiles, the job draft and history in `SharedPreferences` as JSON and
  merges saved runtime fields over `DefaultProfiles`, so new fields keep working after an upgrade.
- `profileFromEditor()` and `buildReadyJob()` are internal pure functions covered by plain JVM
  tests, not only by Robolectric.
- Permission flows: Bluetooth runtime permissions through `RequestMultiplePermissions`, USB host
  permission through a `PendingIntent` broadcast receiver registered with `RECEIVER_NOT_EXPORTED`.

## Testing

- JVM unit tests per module (`gradlew test`), including byte-exact transport tests against a
  loopback server and golden SHA-256 driver fixtures.
- Robolectric tests for the Android `SharedPreferences` layer. The toolchain is pinned to JDK 21
  because Robolectric's instrumenter cannot read newer class files; see `docs/RELEASE_PROCESS.md`.
- `docs/TESTING_WITHOUT_PRINTER.md` describes the manual levels that replace hardware testing.
