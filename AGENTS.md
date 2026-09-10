# AGENTS.md

## Goal

Work on PrintBridge with the smallest safe scope. Prefer focused changes, focused reads, and focused tests. Do not scan the whole repository or run the full build unless the task requires it.

## Project shape

PrintBridge is a Kotlin/Android multi-module project.

Main dependency flow:

`Document -> print-core -> printer-drivers -> printer-transport -> printer`

Modules:
- `app`: Android/Compose UI and device/profile orchestration.
- `print-core`: domain models, print jobs, queueing, chunking, profiles, errors, raster primitives.
- `printer-drivers`: deterministic ESC/POS, TSPL and GOOJPRT byte generation.
- `printer-transport`: generic transport behavior plus Fake/TCP I/O.
- `printer-bluetooth`: Android Bluetooth SPP support.
- `printer-usb`: Android USB printer support.
- `simulator-core`: parses raw printer byte streams and builds virtual previews.
- `simulator-app`: desktop/JVM simulator entry point.
- `windows-print-bridge`: Windows bridge functionality.

Keep these boundaries intact. Avoid moving Android-specific code into JVM/core modules. Avoid introducing reverse dependencies or cycles.

## Before editing

1. Read only the files directly relevant to the task.
2. Check nearby tests before designing a new implementation.
3. Read `docs/ARCHITECTURE.md` only when the change affects module boundaries.
4. Read the relevant focused doc under `docs/` when working on drivers, transports, simulator, Bluetooth, USB, TCP, profiles, or Windows bridge.
5. Do not re-read large files or repository-wide docs if the needed context is already established in the current task.

## Change policy

- Make the smallest coherent change that solves the requested problem.
- Do not refactor unrelated code while fixing a bug or adding a small feature.
- Do not add dependencies unless the task clearly requires them.
- Preserve existing public behavior unless the task explicitly changes it.
- Preserve deterministic byte output in printer drivers unless protocol behavior is intentionally changed.
- Preserve queue serialization, cancellation, timeout handling, chunk ordering, and domain error mapping.
- Never claim physical printer behavior is verified unless it was actually tested on hardware.
- Do not hide warnings merely to make output look clean.
- Do not modify generated build outputs or `local.properties`.

## Testing strategy

Use the narrowest validation first.

For a small change:
1. Run the test task for the affected module when practical.
2. Compile/build only the affected module when that is sufficient.
3. Run broader tests only when shared/core behavior or multiple modules changed.

Examples on Windows:

```powershell
.\gradlew.bat :print-core:test
.\gradlew.bat :printer-drivers:test
.\gradlew.bat :printer-transport:test
.\gradlew.bat :simulator-core:test
.\gradlew.bat :app:assembleDebug
```

Before completing a substantial cross-module or release-related change, run:

```powershell
.\gradlew.bat test assembleDebug
```

Use `clean` only when needed to diagnose stale/generated build state or for an explicit release verification. Do not run `clean` after routine edits.

Current verified build environment is JDK 21 with Gradle 9.7.1 and Android compile/target SDK 36. Keep JVM/Java target compatibility at 21 unless the task explicitly changes the toolchain.

## Test expectations by area

- Driver changes: update/add byte-exact or golden tests.
- Transport changes: cover failure paths, disconnects, timeouts, chunk order/integrity, cancellation, and retry behavior as relevant.
- Profile persistence changes: cover round-trip/default merge/history behavior.
- Simulator parser changes: cover malformed/unknown commands and warnings.
- UI-only changes: prefer targeted compile/build; do not run all JVM tests unless shared logic changed.
- Hardware-specific changes: add deterministic unit/fake tests where possible and clearly mark real hardware validation as UNTESTED if unavailable.

## Repository facts worth preserving

- Package: `com.printbridge.app`.
- Existing protocols: ESC/POS, TSPL, GOOJPRT.
- Existing transports include Fake, TCP, Bluetooth SPP and USB.
- Physical USB/Bluetooth/Network printing has not been fully verified on real printer hardware.
- Release APK signing is not yet a completed production-readiness item.

See `docs/PRINTBRIDGE_INTERMEDIATE_RELEASE_VERIFICATION_REPORT.md` for the latest recorded release audit and known limitations.

## Token/context efficiency

- Prefer `rg`/targeted search over directory-wide file dumps.
- Inspect signatures and nearby code before loading entire large files.
- Do not repeatedly print long Gradle logs; inspect the failing section first.
- After a failed command, diagnose the specific failure before rerunning it.
- Avoid running the same passing test command again unless subsequent edits could affect it.
- When a task is complete, summarize changed files, tests run, and any untested hardware-dependent behavior; do not produce a long repository recap.

## Completion checklist

Before finishing:
- Requested behavior is implemented.
- Relevant focused tests pass.
- Cross-module boundaries remain valid.
- No unrelated files were changed.
- Hardware-dependent claims are labeled accurately.
- For substantial changes, `test assembleDebug` passes or the exact blocker is reported.
