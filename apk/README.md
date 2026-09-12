# APK 0.2.0

Локальные сборки для установки на устройство и для тестирования. **Бинарники не коммитятся** —
папка `apk/` в `.gitignore`, файлы существуют только на этой машине.

Все три собраны из одного дерева, `versionCode = 2`, `versionName = 0.2.0`.

| Файл | Пакет | Что внутри | Подпись |
|---|---|---|---|
| `printbridge-0.2.0-paid-release.apk` | `com.printbridge.app` | Релизная: R8, лимит один лист и вотермарка | `CN=PrintBridge Local Dev` (локальный ключ) |
| `printbridge-0.2.0-paid-debug.apk` | `com.printbridge.app` | То же поведение, но debug-сборка с отладочными символами | `CN=Android Debug` |
| `printbridge-0.2.0-full-debug.apk` | `com.printbridge.app.full` | Полная тестовая: лимитов и вотермарки нет | `CN=Android Debug` |

Пакеты `paid` и `full` разные, поэтому обе версии можно держать установленными одновременно.
Активность у тестовой сборки называется так же, как у релизной (`com.printbridge.app.MainActivity`),
поэтому запускать её нужно по полному компоненту:

```powershell
adb install -r apk\printbridge-0.2.0-paid-release.apk
adb install -r apk\printbridge-0.2.0-full-debug.apk
adb shell am start -n com.printbridge.app.full/com.printbridge.app.MainActivity
```

## Контрольные суммы

```text
26CF71C4553147658277ADE39E088CC53BBE1D5B8C27FA449B71DE491775B823  printbridge-0.2.0-full-debug.apk
17766D871EC6594E8657EB9A3FA195527C238E555B9D7161FB19DE24FB912B4E  printbridge-0.2.0-paid-debug.apk
A6295E816D0013717490F7561CE66FE75DCC6604E5F83BA9AC0DDDA2253524E1  printbridge-0.2.0-paid-release.apk
```

Пересобрать:

```powershell
.\gradlew.bat :app:assemblePaidDebug :app:assembleFullDebug
.\gradlew.bat :app:assemblePaidRelease "-Pprintbridge.requireReleaseSigning=true"
```

## Важно про ключ подписи

Release-сборка подписана **локальным dev-ключом** из `keystore.properties` (файл в `.gitignore`).
Для публикации нужен отдельный production-ключ: смена ключа означает другое приложение, обновить
поверх установленного не получится. Порядок — `docs/RELEASE_PROCESS.md`.
