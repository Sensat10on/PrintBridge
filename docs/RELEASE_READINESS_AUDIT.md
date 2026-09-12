# PrintBridge — Аудит готовности к релизу

> **Статус на 2026-09-12: все проблемы P0–P3 из этого аудита устранены.** Каждое исправление
> проверено сборкой и тестами; отчёт о проверке —
> `PRINTBRIDGE_RELEASE_VERIFICATION_REPORT_0_2_0.md`. Итоги по пунктам:
>
> | Пункт | Итог |
> |---|---|
> | P0-1 падающие тесты | Исправлено: toolchain зафиксирован на JDK 21, 63/63 тестов зелёные |
> | P0-2 неподписанный релиз | Исправлено: signing config, R8, release-gate `requireReleaseSigning` |
> | P1-1 нет release-gate | Исправлено: описанная последовательность + задача, падающая без ключа |
> | P1-2 невоспроизводимость | Исправлено: `dependenciesInfo` отключён, два прогона дают один SHA-256 |
> | P1-3 нет таймаута Bluetooth | Исправлено: `withIoDeadline` + закрытие сокета, покрыто тестом |
> | P1-4 нет CI | Исправлено: `.github/workflows/android.yml` |
> | P1-5 документация | Исправлено: все документы переписаны по коду |
> | P1-6 USB | Исправлено: таймаут из профиля, повторы частичной записи |
> | P1-7 TSPL | Исправлено: кодовая страница + экранирование значений |
> | P1-8 версионирование | Исправлено: версия в `gradle.properties`, процесс в `RELEASE_PROCESS.md` |
> | P2-1 ошибки lint | Исправлено: `lintDebug` без ошибок (7 предупреждений — только версии) |
> | P2-3 backup/permissions/иконка | Исправлено: `allowBackup=false`, `neverForLocation`, adaptive icon |
> | P2-4 лимиты | Исправлено: диапазоны 100..120000 мс, chunkSize ≤ 64 KiB |
> | P3 пункты | Исправлено: ERROR-состояние, stale socket, лимиты discovery/bridge, тесты, разделение `MainActivity` |
>
> Осталось только то, что нельзя проверить без оборудования и production-ключа: см. раздел
> «Перед публикацией» ниже и `docs/PRINTBRIDGE_RELEASE_VERIFICATION_REPORT_0_2_0.md`.

Дата аудита: 2026-09-12
Аудируемая ревизия: `main` @ `699aac1` («Enforce TCP write timeout»), рабочее дерево чистое (кроме неотслеживаемой `.codex-remote-attachments/`).
Область: весь репозиторий (9 модулей), сборочная конфигурация, тесты, артефакты, документация.

Окружение аудита (фактическое):

| Параметр | Значение |
|---|---|
| Gradle | 9.7.1 (через `gradlew.bat`) |
| JVM launcher/daemon | JDK 26.0.2.1 (Oracle) |
| AGP | 9.4.0 |
| Kotlin | 2.2.20 (сборка) / 2.4.0 (Gradle embedded) |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| Установленные JDK | только `C:\Program Files\Java\jdk-26.0.2.1` |

## Вердикт

**К релизу не готов.** Один релизный блокер уровня «сборка не зелёная» и один блокер уровня «артефакт нельзя распространять». Остальное — управляемость сборки, риски протокола и документация.

Предыдущий отчёт `PRINTBRIDGE_INTERMEDIATE_RELEASE_VERIFICATION_REPORT.md` утверждает «43 unit-теста: failures=0» и «`clean test assembleDebug` PASS». **На текущей ревизии и текущем окружении это не воспроизводится: 3 теста падают.**

## Что подтверждено заново (PASS)

| Проверка | Команда | Результат |
|---|---|---|
| Сборка Debug APK | `gradlew.bat assembleDebug` | BUILD SUCCESSFUL |
| Сборка Release APK | `gradlew.bat assembleRelease` | BUILD SUCCESSFUL, `lintVitalRelease` пройден |
| Тесты JVM-модулей (40 тестов) | `gradlew.bat test` по модулям | 40 passed, 0 failed |
| Порядок байтов транспорта | `TransportTests`, `TransportAuditTests` | побайтовое сравнение на 128 KiB и raster-подобной нагрузке проходит |
| Golden-байты драйверов | `DriverTests` (SHA-256) | ESC/POS, TSPL, GOOJPRT совпадают |
| Парсеры симулятора | `SimulatorTests` | ESC/POS, TSPL, GOOJPRT, malformed/unknown — 9/9 |
| Персистентность профилей/истории | `ProfileStoreTests` | падают по инфраструктурной причине (см. P0-1), не по логике |
| Направление зависимостей | `settings.gradle.kts` | циклов нет; `print-core` ни от чего не зависит |
| Отсутствие отладочного кода | grep | нет `TODO/FIXME/Log.d/printStackTrace` в `main` |

Размеры и хеши артефактов, полученные в этом аудите:

| Артефакт | Байт | SHA-256 |
|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 11 247 413 | `FD83384AED556CE97F5CC241B79D39FFA2F15CACC8839CD0456D12E840795357` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 7 519 110 | `E3F4D3F91F8A2BC9CF03A50FA4EAD071B7AFB3E91D1CD0BEA0EE25DCFAC25569` |

Размеры совпадают с предыдущим отчётом, хеши — нет (см. P1-2).

Тестовые результаты (детально): всего заявлено и обнаружено 43 теста — `app` 3, `print-core` 4, `printer-bluetooth` 7, `printer-drivers` 7, `printer-transport` 13, `simulator-core` 9. Провалено 3.

---

## P0 — блокеры релиза

### P0-1. `:app:testDebugUnitTest` падает: 3 из 43 тестов не проходят

Воспроизведение:

```powershell
.\gradlew.bat clean test assembleDebug
```

Результат:

```
> Task :app:testDebugUnitTest FAILED
ProfileStoreTests > defaultsAreRestoredWhenNothingWasSaved FAILED
    java.lang.IllegalArgumentException at ClassReader.java:200
ProfileStoreTests > createEditDuplicateDeleteAndRestartRoundTrip FAILED
ProfileStoreTests > profileFieldsSurvivePersistenceRoundTrip FAILED
BUILD FAILED in 3m 20s
```

Полное сообщение (`app/build/test-results/testDebugUnitTest/TEST-com.printbridge.app.ProfileStoreTests.xml`):

```
java.lang.IllegalArgumentException: Unsupported class file major version 70
  at org.objectweb.asm.ClassReader.<init>(ClassReader.java:200)
  at org.robolectric.internal.bytecode.ClassNodeProvider.createClassNode(ClassNodeProvider.java:24)
  ...
  at org.robolectric.android.internal.AndroidTestEnvironment.resetState(AndroidTestEnvironment.java:605)
```

Причина: единственные Robolectric-тесты в репозитории пытаются инструментировать классы, произведённые JDK 26 (class file major 70), а ASM, поставляемый с `org.robolectric:robolectric:4.16`, их не понимает. Собственный код компилируется в major 68 (`jvmTarget = 24` подтверждён чтением заголовка `ProfileStoreTests.class`), то есть проблема именно в версии JDK, на которой запускается тестовый JVM, а не в исходниках.

Усугубляющий фактор: в проекте **вообще нет фиксации toolchain**. В `gradle.properties`, корневом `build.gradle.kts` и модульных скриптах нет ни `java.toolchain`, ни `org.gradle.java.home`, ни `kotlin.jvmToolchain`. В README зафиксирован JDK-независимый запуск, а в отчёте от 2026-09-07 написано «JDK 26 выставлял Java target 26, тогда как Kotlin откатывался на target 24. Для JVM-модулей и Android-модулей зафиксирован совместимый target 24» — то есть проблему уже один раз «лечили» сменой target, и она вернулась в другой форме. На машине установлен только JDK 26, откатиться некуда.

Последствия: `clean test assembleDebug` не зелёный; любая проверка персистентности профилей, дефолтов и draft/history в CI будет красной; заявление о 43/43 в верификационном отчёте недостоверно.

Что сделать:
1. Зафиксировать toolchain явно, например в корневом `build.gradle.kts`:
   `kotlin { jvmToolchain(24) }` и/или `java { toolchain { languageVersion = JavaLanguageVersion.of(24) } }` для JVM-модулей, плюс `test { javaLauncher = ... }` для Android-модуля.
2. Либо перейти на JDK 21/17 LTS и указать это в README и CI.
3. Проверить, снимает ли проблему `robolectric:4.17` (lint уже предлагает обновление) — но обновлением библиотеки toolchain-дыру закрывать не стоит.
4. Перезапустить `clean test assembleDebug` и приложить новый вывод к отчёту.

### P0-2. Release APK не подписан, конфигурации подписи нет

`gradlew.bat :app:signingReport`:

```
Variant: release
Config: none
```

Внутри `app-release-unsigned.apk` отсутствуют записи `META-INF/*.RSA|SF` (проверено чтением ZIP). В репозитории нет ни `signingConfigs`, ни `keystore.properties`, ни ссылок на `minifyEnabled`/`shrinkResources`/`proguardFiles` (grep по всем `build.gradle.kts` — 0 совпадений).

Последствия: артефакт нельзя ни опубликовать, ни установить как обновление. Это уже отмечено в разделе «Перед production-релизом обязательно» прошлого отчёта, но так и не сделано; для *релиза* это блокер, а не «ограничение».

Что сделать: `signingConfigs { release { ... } }` с ключом вне репозитория, `isMinifyEnabled`, `isShrinkResources`, базовые `proguard-rules.pro`, и политика `versionCode`/`versionName` (`versionCode = 1`, `versionName = "0.1.0"` сейчас захардкожены в `app/build.gradle.kts`).

---

## P1 — критично, но не блокирует сборку

### P1-1. `assembleRelease` не проверяет подпись и не является release-gate

`assembleRelease` проходит, `lintVitalRelease` проходит, но задача не проверяет наличие подписи, не проверяет версию и не публикует артефакт. Роль release-gate фактически нигде не закреплена (нет CI, см. P1-4). Рекомендуется отдельная задача/скрипт, который после сборки: валидирует подпись, пишет SHA-256, сверяет `versionCode` и падает при расхождении.

### P1-2. Сборка не воспроизводима

Debug APK на той же ревизии даёт другой SHA-256, чем в отчёте от 2026-09-07 (`72BA498A…` → `FD83384A…`) при идентичном размере. Release — тот же случай (`A6CED58C…` → `E3F4D3F9…`). Утверждение прошлого отчёта о фиксированных хешах артефактов не выполняется: либо сборка невоспроизводима (нет `--no-build-cache`, нет фиксации timestamps), либо отчёт описывает другую ревизию. Для релиза нужен либо reproducible-build профиль, либо явная формулировка «хеш действителен только для этой машины».

### P1-3. У Bluetooth-транспорта нет write/connect-таймаута

`printer-bluetooth/src/main/java/com/printbridge/bluetooth/BluetoothSpp.kt` — `write()` делает `active.outputStream.write(data)` без таймаута и без ограничения по времени; `connect()` полагается на таймаут ОС. Тот же класс проблемы уже исправлялся для TCP (`TcpPrinterTransport.writeWithTimeout`, коммит `699aac1`), но для Bluetooth аналога нет.

Последствие: если BT-принтер перестал читать (замятие, разряд, выход из зоны), запись может встать надолго/навсегда. `PrintQueue.enqueue` держит `mutex.withLock` на всё время задания, значит вся очередь печати в приложении блокируется — без возможности отмены через UI. Для «готовности к релизу» это главный незакрытый функциональный риск после тестов.

Что сделать: аналог deadline-обёртки для `OutputStream` (или watchdog-корутина с `withTimeout`), профильное поле `writeTimeoutMs` уже есть в модели и уже используется USB.

### P1-4. Нет CI и нет фиксации версий зависимостей

В репозитории нет ни `.github/workflows`, ни любого другого CI-конфига при наличии remote `origin = https://github.com/Sensat10on/PrintBridge.git`. Все версии зависимостей заданы инлайн (`activity-compose:1.11.0`, `material3:1.4.0`, `ui:1.9.1`, `robolectric:4.16`, корутины `1.10.2`) — нет version catalog и нет dependency locking.

Последствие ровно то, что уже произошло с P0-1: окружение уехало, сборка покраснела, узнали об этом вручную. Минимальный CI (JDK, `clean test assembleDebug`, проверка отсутствия `FATAL EXCEPTION`, сохранение SHA-256) закрывает и P0-1, и P1-2.

### P1-5. Документация противоречит реализации

Проверено чтением кода:

| Документ | Утверждение | Факт |
|---|---|---|
| `docs/MILESTONE3_GOOJPRT_PREP.md:51` | «USB is still a declared transport type, not an Android runtime implementation» | `printer-usb/UsbPrinterTransport.kt` реализует open/claim/bulkTransfer/disconnect, в UI есть `UsbPanel` |
| `docs/MILESTONE3_GOOJPRT_PREP.md:52` | «the app does not yet expose host/port profile fields» | `MainActivity.kt:445-473` — поля host/port/connect timeout/write timeout есть |
| `docs/BLUETOOTH_SPP.md` | «Bluetooth Classic SPP is Milestone 2. It is not implemented» | полноценный `BluetoothSppPrinterTransport` + виртуальный принтер-сервер |
| `docs/KNOWN_LIMITATIONS.md` | «не проверены … Bluetooth, BLE, USB» | Bluetooth и USB реализованы и покрыты контрактными тестами (проверено на железе — да, не проверены) |
| `docs/ARCHITECTURE.md` | перечисляет Milestone 1 модули | не упомянуты `printer-usb`, `printer-bluetooth`, `windows-print-bridge` |
| `docs/PRINTER_PROFILES.md` | «ships only four generic profiles» | в `DefaultProfiles.all` пять профилей |
| `README.md:40` | «use FAKE PRINT or configure TCP in later UI work» | TCP-панель с печатью и discovery уже в UI |
| `README.md:10,16,22,28` | абсолютные пути к `C:\Users\Alex\.gradle\wrapper\dists\gradle-9.7.1-…\gradle.bat` | не работает ни на одной другой машине, хотя `gradlew.bat` присутствует и работает |

Для релиза это значит: внешний пользователь по документации не поймёт, что уже работает, а что нет.

### P1-6. USB: таймаут берётся из чужого поля, нет обработки частичной записи

- `MainActivity.kt:688` передаёт в транспорт `profile.writeTimeoutMs`, а в редакторе профиля (`profileFromEditor`) это поле не редактируется — заполняются `networkWriteTimeoutMs`. То есть пользовательская настройка таймаута на USB не влияет, всегда 5000 мс из дефолта конструктора.
- `UsbPrinterTransport.write()` (`printer-usb/.../UsbPrinterTransport.kt:99-107`) трактует любой короткий `bulkTransfer` как фатальную ошибку задания. Транзиентный таймаут USB даёт `written = -1` и сразу FAILED, без повторов.
- `printer-usb/build.gradle.kts` объявляет `minSdk = 23`, при `minSdk = 26` у приложения. Значение вводит в заблуждение, если модуль переиспользуют.

### P1-7. TSPL: кириллица превращается в «?», экранирование неполное

- `printer-drivers/src/main/kotlin/com/printbridge/drivers/TsplDriver.kt:62` — `line()` пишет через `Charsets.US_ASCII`. Всё, что вне ASCII, заменяется на `?`. UI приложения русскоязычный, и текст задания (`Название товара`, любые русские строки) уходит на TSPL-этикетку искажённым. ESC/POS и GOOJPRT пишут в UTF-8 — поведение между драйверами несогласованно.
- `TsplDriver.kt:66` — `escape()` экранирует только `"`. Обратный слэш и `\n`/`\r` не экранируются; сейчас все поля ввода `singleLine`, поэтому это потенциальный, а не активный дефект, но для публичного релиза стоит закрыть (экранирование + запрет управляющих символов).

### P1-8. Нет политики версионирования

`versionCode = 1`, `versionName = "0.1.0"` захардкожены. Нет правила, как назначается версия, нет changelog. Для релиза нужен хотя бы описанный процесс.

---

## P2 — важно, не блокирует

### P2-1. Lint падает на двух ошибках

`gradlew.bat :app:lintDebug` → BUILD FAILED, 2 errors / 12 warnings / 2 hints:

1. `local.properties:1 PropertyEscape` — `sdk.dir=C:\\Users\\...` требует экранирования двоеточия (`C\:\\...`). Файл не в репозитории (в `.gitignore`), но раз он роняет lint, стоит поправить локально либо исключить из области lint.
2. `MainActivity.kt:107 UnspecifiedRegisterReceiverFlag` — `registerReceiver(usbPermissionReceiver, filter)` на ветке `SDK_INT < TIRAMISU` зарегистрирован без флага. На `TIRAMISU+` флаг есть (`RECEIVER_NOT_EXPORTED`), на старых версиях lint требует `ContextCompat.registerReceiver`. Замечание валидное: `USB_PERMISSION` — это unprotected broadcast в вашем package, то есть потенциально подделываемый извне на Android ≤ 12.

Предупреждения, которые стоит осознанно принять или исправить: `MissingApplicationIcon` (иконки приложения нет — в манифесте нет `android:icon`, ресурсов `mipmap` в проекте нет), `OldTargetApi`, устаревшие версии Compose/activity, `UseKtx`, `AutoboxingStateCreation`.

### P2-2. `assembleDebug` не выполняет тесты; `assembleRelease` не проверяет подпись

Релиз-пайплайн нигде не описан как последовательность задач. Рекомендуемая минимальная последовательность release-gate:

```
gradlew.bat clean test assembleDebug assembleRelease lintDebug
```

с явной проверкой подписи и хешей.

### P2-3. `allowBackup="true"` и незапрошенное разрешение

- `app/src/main/AndroidManifest.xml:8` — `allowBackup="true"`. Через adb-бэкап/облачный бэкап уезжают `SharedPreferences` с профилями, host-адресами и историей заданий. Для релиза — либо `false`, либо `dataExtractionRules`.
- Манифест запрашивает `BLUETOOTH_SCAN` (`AndroidManifest.xml:6`), но код никогда не вызывает `startDiscovery()`: используется только `adapter.bondedDevices` (`BluetoothSpp.kt:46`). Разрешение лишнее, а на Android 12+ из-за отсутствия `android:usesPermissionFlags="neverForLocation"` оно тянет за собой запрос доступа к геолокации.

### P2-4. Нерабочая ветка pre-`networkWriteTimeoutMs`

`profileFromEditor` валидирует таймауты (строки 1183–1186), но UI не ограничивает верхнюю границу; значение вида `2147483647` уйдёт в `TcpPrinterTransport` и превратит deadline в `System.nanoTime() + timeoutMs * 1_000_000L` без переполнения, зато с практически бесконечным ожиданием. Стоит ограничить разумным диапазоном (например, 100..60000 мс) и на уровне редактора, и на уровне транспорта.

---

## P3 — низкий приоритет / долг

1. **Состояние `ERROR` затирается.** `PrintQueue.enqueue` в `catch` вызывает `transport.disconnect()`, который у всех транспортов выставляет `DISCONNECTED`, затирая только что установленный `ERROR`. UI это не показывает (он берёт `job.state`), но любой будущий наблюдатель `transport.state` получит неверную картину.
2. **Bluetooth virtual printer server держит stale-ссылку.** В `BluetoothVirtualPrinterServer.start()` лямбда читает `serverSocket?.accept()` из поля; `stop()` обнуляет поле, но уже запущенный `accept()` продолжит ждать. Освобождение происходит только после `close()` в `finally`, то есть фактически при следующем подключении. Также `readClientBytes` читает поток без ограничения объёма и без таймаута.
3. **`NetworkPrinterDiscovery`** открывает до 4 × 254 = 1016 сокетов одновременно (`async(Dispatchers.IO)` на каждый порт каждого host), без общей отмены, с фиксированным таймаутом 250 мс и без дедупликации подсетей. На большом LAN это риск исчерпания дескрипторов; пользователю нельзя отменить поиск.
4. **`windows-print-bridge`** слушает `0.0.0.0:9191` без аутентификации и без ограничения размера задания (`readBytes()` безлимитен), spawn-ит неподконтрольный поток на каждое подключение и не является демоном. Документация предупреждает про firewall, но не про то, что это открытый порт печати в LAN.
5. **`chunkSize` не ограничен сверху.** `WriteChunker` делает `coerceAtLeast(1)`, но не `coerceAtMost`. `Int.MAX_VALUE` в профиле приводит к переполнению `offset + chunkSize` и исключению — задание упадёт в FAILED вместо понятной ошибки валидации.
6. **`minSdk` модулей расходится:** `printer-usb` 23, `app`/`printer-bluetooth` 26. Стоит унифицировать или документировать.
7. **`MainActivity.kt` — 1203 строки** с 4 непохожими панелями транспорта и 6 сборщиками заданий внутри одного файла. Тестов на UI нет вообще: `profileFromEditor`, `buildReadyJob`, `inspect` не покрыты. При релизе это главный источник регрессий.
8. **`ProfileStoreTests.profileFieldsSurvivePersistenceRoundTrip`** не задаёт `writeTimeoutMs`, `usbVid`, `usbPid`, `bluetoothDeviceAddress`, `knownQuirks` — «полный» round-trip фактически не полный.
9. **`SimpleDateFormat` создаётся на каждую рекомпозицию** (`MainActivity.kt:1008`).
10. **Бинарные вложения в рабочем дереве:** неотслеживаемая `.codex-remote-attachments/` (~87 КБ).

---

## Чек-лист перед релизом

Обязательно (P0/P1):

- [ ] Зафиксировать JDK/toolchain, добиться зелёного `clean test` — 43/43.
- [ ] Настроить release signing, minify/shrink, proguard-правила; политика `versionCode`/`versionName`.
- [ ] Добавить CI: JDK, `clean test assembleDebug assembleRelease lintDebug`, сохранение SHA-256.
- [ ] Добавить таймаут записи для Bluetooth SPP и проверить, что очередь не может встать навсегда.
- [ ] Починить USB: таймаут из профиля, обработка частичной записи/повторов.
- [ ] Починить кодировку TSPL (кириллица) и экранирование строк.
- [ ] Привести документацию в соответствие с кодом, переписать README на `gradlew.bat` без абсолютных путей.
- [ ] Либо обеспечить воспроизводимость артефактов, либо убрать хеши артефактов из отчётов.

Перед публикацией (P2/P3):

- [ ] Закрыть 2 ошибки lint; осознанно принять или исправить предупреждения.
- [ ] `allowBackup=false` или `dataExtractionRules`; убрать `BLUETOOTH_SCAN` либо добавить `neverForLocation`.
- [ ] Добавить иконку приложения.
- [ ] Собрать подписанный APK и проверить установку на Samsung S25 / Redmi Pad 2 (чистая установка, upgrade, process death).
- [ ] Проверить печать на физических USB / Bluetooth SPP / TCP-принтерах, включая отказ в разрешении, unplug/reconnect, timeout, частичную запись и повтор.

---

## Приложение: команды воспроизведения

```powershell
cd D:\Programming\BT_printer

# 1. Полная сборка и тесты — ПАДАЕТ на :app:testDebugUnitTest (P0-1)
.\gradlew.bat clean test assembleDebug --console=plain

# 2. Только тесты JVM-модулей — 40/40 PASS
.\gradlew.bat :printer-transport:test :simulator-core:test :print-core:test :printer-drivers:test :printer-bluetooth:test

# 3. Артефакты
.\gradlew.bat assembleDebug assembleRelease
.\gradlew.bat :app:signingReport     # release: Config: none  (P0-2)

# 4. Lint
.\gradlew.bat :app:lintDebug         # 2 errors, 12 warnings, 2 hints  (P2-1)
```
