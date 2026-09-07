# PrintBridge Intermediate Release Verification Report

Дата аудита: 2026-09-07  
Область: локальный промежуточный релиз, без физического принтера.

## Итог

Статус проекта: **условно готов к промежуточному локальному релизу с ограничениями**; production readiness не подтверждён из-за отсутствия hardware/device smoke и строгого TCP write-timeout.

Подтверждённый дефект сборочного окружения исправлен: JDK 26 выставлял Java target 26, тогда как Kotlin откатывался на target 24. Для JVM-модулей и Android-модулей зафиксирован совместимый target 24. Это не добавляет функциональности и не меняет runtime API приложения.

## Проверки

| Область | Статус | Доказательство / исправление / тест |
|---|---|---|
| Структура модулей и направление зависимостей | PASS | `settings.gradle.kts`; app зависит от core/drivers/transport/bluetooth/usb/simulator-core, transport зависит только от core; циклов в project graph не обнаружено. |
| AndroidManifest и launcher | PASS | `app/src/main/AndroidManifest.xml`: `MainActivity`, `MAIN` + `LAUNCHER`, `exported=true`. |
| Bluetooth runtime permissions | PASS | `MainActivity.kt`, `BluetoothPermissions.runtimePermissions()`; запрос через `RequestMultiplePermissions`. Физический Bluetooth-принтер — UNTESTED. |
| USB discovery и endpoint selection | PASS | `UsbPrinterRepository` фильтрует устройства по writable bulk OUT endpoint; неподдерживаемый endpoint даёт понятную доменную ошибку. |
| USB системное разрешение | PASS | `MainActivity.requestUsbPermission()` использует `UsbManager.requestPermission()` и broadcast callback; отказ передаётся в UI. Реальный USB flow — UNTESTED. |
| USB reconnect / connection loss | PASS | transport закрывает и очищает connection/endpoint; повторный `connect()` выбирает endpoint заново. Реальный reconnect — UNTESTED. |
| Profile persistence / restore | PASS | `ProfileStore.kt`; JSON round-trip, defaults merge, draft/history persistence покрыты `ProfileStoreTests`. |
| Transport selection | PASS | `MainActivity.kt` разделяет FAKE, TCP, Bluetooth SPP и USB; UI показывает выбранный профиль/протокол. |
| Network host/port persistence and validation | PASS | поля профиля сохраняются; порт проверяется диапазоном 1..65535 в `profileFromEditor()`. |
| Network discovery | PASS | `NetworkPrinterDiscovery`; shared printer ports и invalid subnet покрыты `TransportTests`. Реальные LAN-принтеры — UNTESTED. |
| TCP connect timeout | PASS | connect timeout передаётся в `Socket.connect`; loopback connection покрыта `tcpTransportWritesToLoopbackServer`. |
| TCP write timeout | FAIL | `Socket.setSoTimeout()` ограничивает чтение, но не блокирующий `OutputStream.write()`; текущий код не даёт строгой гарантии write timeout при зависшем TCP peer. Требуется отдельный non-blocking `SocketChannel`/writer с deadline и regression-тестом. |
| Retry / resend | PASS | queue serializes jobs and transport errors become FAILED; retry after an error is available by re-enqueue/retry UI flow. Real printer retry — UNTESTED. |
| Disconnect during transfer | PASS | Fake fault `disconnectAfterBytes` covered in `TransportAuditTests`/`TransportTests`; job never completes after loss. |
| Slow printer / tiny buffer / disconnect-after-N | PASS | `TransportAuditTests.faultScenariosExposeDomainErrorsAndNeverComplete`, `slowPrinterStillCompletesWhenNoTimeoutIsInjected`. |
| Cancellation and queue serialization | PASS | `cancellationDuringMultiChunkJobIsCancelled` and `queueSerializesTwoJobsWithoutInterleaving`. |
| Chunking byte order/integrity | PASS | exact byte comparisons for multiple chunk sizes and 128 KiB/large raster-like payloads in `TransportAuditTests` and `TransportTests`. |
| ESC/POS, TSPL, GOOJPRT driver bytes | PASS | existing golden/driver tests in `printer-drivers/src/test/kotlin/com/printbridge/drivers/DriverTests.kt`; no regressions. |
| Raster / large jobs | PASS | raster engine and large byte-exact transport tests pass. |
| Simulator parsing malformed/unknown commands | PASS | `simulator-core` tests pass for ESC/POS, TSPL and GOOJPRT parsing and warnings. |
| Visual preview ESC/POS/TSPL | PASS | app preview calls the corresponding simulator parser; UI source in `MainActivity.kt`. |
| History and repeat action | PASS | history persistence and deduplication are covered by `ProfileStoreTests`; UI retains history and exposes resend action. |
| User-facing error states | PASS | queue maps domain errors to FAILED/CANCELLED; UI displays Russian status text instead of stack traces. |
| Restart recovery | PASS | draft, profiles and history are loaded from `SharedPreferences`; round-trip covered by `ProfileStoreTests`. Full process-death device test — UNTESTED. |
| Small/large screen UI | PASS | root content uses scroll state and custom scrollbar; diagnostic/history screens remain present. Screenshot/device layout verification — UNTESTED. |
| Missing Bluetooth/USB/network device | PASS | repositories return empty lists and transports expose domain errors; no device is treated as a successful print. Physical absence scenarios — UNTESTED. |
| Gradle warning audit | PASS | current repository uses AGP 9.4.0. The former internal AGP warning was not reproduced after the target fix; remaining warnings are Kotlin Bluetooth deprecations and native-symbol stripping, not hidden. |
| `clean test assembleDebug` | PASS | Gradle 9.7.1, all configured tests passed, Debug APK assembled. |
| `assembleRelease` | PASS | release APK assembled successfully; unsigned artifact below. |
| APK installation on Samsung S25 | UNTESTED | `adb devices` returned no connected devices in this session. |
| APK installation on Redmi Pad 2 | UNTESTED | `adb devices` returned no connected devices in this session. |
| Physical USB/Bluetooth/Network printing | UNTESTED | No physical printer is available; no PASS is claimed. |

## Build artifacts

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Debug size: 11,247,413 bytes
- Debug SHA-256: `72BA498ABD4E4E787C3BE390012F002BECF36786DC6E9DC3BE31323FD9831CC5`
- Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`
- Release size: 7,519,110 bytes
- Release SHA-256: `A6CED58CD95EEBE9A005CEBBEC0ECAC36170E6C4694776EB701EB988977CC677`
- Package name: `com.printbridge.app`
- versionCode: `1`
- versionName: `0.1.0`
- target/compile SDK: `36`

## Изменённые файлы

- `build.gradle.kts` — единый JVM target 24 для JVM-модулей.
- `app/build.gradle.kts` — Android Java source/target 24.
- `printer-bluetooth/build.gradle.kts` — Android Java source/target 24.
- `printer-usb/build.gradle.kts` — Android Java source/target 24.
- `docs/PRINTBRIDGE_INTERMEDIATE_RELEASE_VERIFICATION_REPORT.md` — этот отчёт.

`local.properties` создан локально для указания Android SDK и не является исходным артефактом релиза.

Всего выполнено 43 unit-теста: failures=0, errors=0, skipped=0.

## Оставшиеся ограничения

- Нет физического принтера: USB, Bluetooth SPP и raw TCP печать не подтверждены на железе.
- Нет подключённых Samsung S25/Redmi Pad 2 в текущей ADB-сессии: install/launch/logcat/UI smoke не выполнены.
- Release APK unsigned; для распространения нужна release signing configuration.
- Bluetooth API содержит ожидаемые deprecation warnings для `BluetoothAdapter.getDefaultAdapter()`.
- AGP/native strip warnings не скрывались. Их следует отдельно проверить на CI с целевой версией JDK/NDK.

## Перед production-релизом обязательно

1. Выполнить smoke-тесты на Samsung S25 и Redmi Pad 2 с чистой установкой, upgrade и process death.
2. Протестировать реальные USB, Bluetooth SPP и raw TCP принтеры, включая отказ permission, unplug/reconnect, timeout, partial write и повторную отправку.
3. Настроить release signing, versionCode/versionName policy и reproducible artifact publication.
4. Добавить CI на поддерживаемом JDK и зафиксировать проверку APK manifest, SHA-256 и отсутствие FATAL EXCEPTION.
5. Проверить layout/accessibility на малом телефоне и большом планшете скриншотными/UI-тестами.
