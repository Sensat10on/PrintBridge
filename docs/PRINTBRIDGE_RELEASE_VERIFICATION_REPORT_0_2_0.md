# PrintBridge Release Verification Report 0.2.0

Дата: 2026-09-12  
Ревизия: рабочее дерево на базе `699aac1`  
Область: локальный релиз-кандидат, без физического принтера и без физических устройств.

Предыдущие отчёты: `PRINTBRIDGE_INTERMEDIATE_RELEASE_VERIFICATION_REPORT.md` (ревизия `263f76f`,
устарел), `RELEASE_READINESS_AUDIT.md` (аудит, по которому выполнены исправления).

## Итог

**Готов к локальному промежуточному релизу.** Все автоматические проверки зелёные, артефакт
подписан и воспроизводим. Production readiness по-прежнему не подтверждён: нет smoke-тестов на
физических устройствах и нет печати на реальные принтеры.

Что изменилось относительно прошлого отчёта:

| Было | Стало |
|---|---|
| `clean test assembleDebug` падал: 3 из 43 тестов | `clean test assembleDebug assembleRelease lintDebug` — BUILD SUCCESSFUL, 63/63 |
| Release APK без подписи | Подписан (v2 + v3), сборка падает без ключа при `-Pprintbridge.requireReleaseSigning=true` |
| Хеши артефактов не воспроизводились | Два прогона `assembleRelease --rerun-tasks` дают идентичный SHA-256 |
| Нет фиксации JDK | Toolchain зафиксирован на JDK 21 и скачивается Gradle автоматически |
| Нет CI | `.github/workflows/android.yml` |
| У Bluetooth нет таймаута записи | `withIoDeadline`, сокет закрывается по таймауту |
| USB: короткая запись = отказ задания | Повторы по остатку, ограниченный deadline |
| TSPL: не-ASCII превращался в `?` | Настраиваемая кодовая страница, ISO-8859-1 по умолчанию |
| Документация противоречила коду | Все документы синхронизированы |

## Окружение аудита

| Параметр | Значение |
|---|---|
| Gradle | 9.7.1 (wrapper) |
| JVM сборки | JDK 26.0.2.1 (launcher) |
| Toolchain / тестовая JVM | JDK 21 (provisioned by Gradle, `~/.gradle/jdks`) |
| AGP / Kotlin | 9.4.0 / 2.2.20 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| versionCode / versionName | 2 / 0.2.0 |

## Выполненные проверки

```powershell
.\gradlew.bat clean test assembleDebug assembleRelease lintDebug "-Pprintbridge.requireReleaseSigning=true"
# BUILD SUCCESSFUL in 2m 5s, 300 actionable tasks: 278 executed
```

| Область | Статус | Доказательство |
|---|---|---|
| Unit-тесты, все модули | PASS | 63 теста, failures=0, errors=0, skipped=0 (10 сьютов) |
| Debug APK | PASS | `app-debug.apk`, 11 266 725 байт |
| Release APK | PASS | `app-release.apk`, 1 187 955 байт (R8 + shrinkResources) |
| Подпись релиза | PASS | `apksigner verify`: Verifies, v2=true, v3=true |
| Воспроизводимость | PASS | два прогона `--rerun-tasks` → идентичный SHA-256 |
| `lintDebug` | PASS | 0 ошибок; 8 предупреждений, все — «доступна более новая версия» |
| Golden-байты драйверов | PASS | SHA-256 для ESC/POS, TSPL, GOOJPRT не изменились |
| Таймаут записи Bluetooth/USB | PASS | `DeadlineTests.blockingWorkPastTheDeadlineIsAbandonedAndReported` |
| Лимиты chunkSize | PASS | `DeadlineTests.chunkerClampsAbsurdChunkSizesInsteadOfOverflowing` |
| Валидация профиля | PASS | `ProfileEditorTests.outOfRangeValuesAreRejected` |
| Генерация заданий для всех протоколов | PASS | `ProfileEditorTests.readyJobsAreGeneratedForEveryProtocolAndTemplate` |
| TSPL: кодировка и инъекция команд | PASS | `DriverTests.tsplNonAsciiTextIsEncodedWithTheRequestedCodePageInsteadOfBeingReplaced`, `tsplPayloadCannotInjectCommandsOrBreakStringLiterals` |
| Персистентность профилей | PASS | `ProfileStoreTests.profileFieldsSurvivePersistenceRoundTrip` (все поля), `savedRuntimeFieldsSurviveDefaultsMerge` |
| Устойчивость к битому JSON | PASS | `ProfileStoreTests.malformedStoredJsonFallsBackToDefaultsInsteadOfCrashing` |
| Установка на Samsung S25 / Redmi Pad 2 | UNTESTED | Нет подключённых устройств |
| Печать на физический USB/BT/TCP принтер | UNTESTED | Нет оборудования |

## Артефакты

| Артефакт | Байт | SHA-256 |
|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 11 266 725 | `64CFD0296AD9AC453BEEC43026037BDFBEBDD4BD4DD0C1FADC329BFD20B59B41` |
| `app/build/outputs/apk/release/app-release.apk` | 1 187 955 | `B7D705B0838FA2C366803599745E4EC8C922C28C085780923E81067FB4603197` |

Подпись release: `CN=PrintBridge Local Dev, OU=Development, O=PrintBridge, C=US`,
SHA-256 отпечаток сертификата `49b88f9c4594346830f59e88a5a4746d45f208da728a32b3e2e83d246272f712`.

**Хеши действительны только для локального dev-ключа.** Для публикации нужен production-ключ:
другой ключ даёт другой APK и другой хеш. Процесс — `docs/RELEASE_PROCESS.md`.

## Изменённые и добавленные файлы

Сборка и релиз:

- `build.gradle.kts`, `settings.gradle.kts` — toolchain JDK 21, foojay resolver
- `app/build.gradle.kts` — signing config, R8, `dependenciesInfo`, версия из `gradle.properties`
- `app/proguard-rules.pro`, `app/lint.xml`, `gradle.properties`
- `tools/create-release-keystore.ps1`, `keystore.properties.example`, `.gitignore`
- `.github/workflows/android.yml`

Код:

- `print-core/.../Deadline.kt` — `withIoDeadline`, лимиты `WriteChunker`
- `printer-bluetooth/.../BluetoothSpp.kt` — таймауты connect/write, сохранение ERROR
- `printer-bluetooth/.../BluetoothVirtualPrinterServer.kt` — корректное закрытие сокета, лимит чтения
- `printer-usb/.../UsbPrinterTransport.kt` — deadline, повторы частичной записи
- `printer-transport/.../TcpPrinterTransport.kt`, `NetworkPrinterDiscovery.kt` — ERROR-состояние, лимит параллельных проб
- `printer-drivers/.../TsplDriver.kt` — кодовая страница, экранирование значений
- `windows-print-bridge/...` — лимит размера задания, read timeout, предупреждение о bind
- `app/.../MainActivity.kt`, `TransportPanels.kt`, `JobBuilders.kt` — разделение UI и логики, поле таймаута транспорта, `ContextCompat.registerReceiver`

Ресурсы:

- `app/src/main/AndroidManifest.xml` — `allowBackup=false`, `neverForLocation`, иконка
- `app/src/main/res/mipmap/`, `res/drawable/ic_launcher_foreground.xml`, `res/values/colors.xml`, `res/xml/`

Тесты: `print-core/.../DeadlineTests.kt`, `app/.../ProfileEditorTests.kt`,
`printer-drivers/.../DriverTests.kt`, `app/.../ProfileStoreTests.kt`.

Документация: `README.md`, `docs/ARCHITECTURE.md`, `docs/BLUETOOTH_SPP.md`,
`docs/KNOWN_LIMITATIONS.md`, `docs/MILESTONE3_GOOJPRT_PREP.md`, `docs/PRINTER_PROFILES.md`,
`docs/SIMULATOR.md`, `docs/TCP_PRINTING.md`, `docs/TESTING_WITHOUT_PRINTER.md`,
`docs/WINDOWS_PRINT_BRIDGE.md`, `docs/RELEASE_PROCESS.md`, `docs/RELEASE_READINESS_AUDIT.md`.

## Что осталось до production

1. Smoke-тесты на физических устройствах: чистая установка, upgrade, process death, отсутствие
   `FATAL EXCEPTION` в logcat.
2. Печать на реальные USB, Bluetooth SPP и TCP-принтеры, включая отказ в разрешении,
   unplug/reconnect, таймаут, частичную запись и повторную отправку.
3. Production-ключ подписи и хранение паролей в секретах CI.
4. Проверка layout/accessibility на малом телефоне и большом планшете.
5. Кодовая страница TSPL для кириллицы: подтвердить на железе и, при необходимости, добавить
   поле профиля (`docs/KNOWN_LIMITATIONS.md`).
