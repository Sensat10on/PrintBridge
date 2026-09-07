# PrintBridge

PrintBridge is a Kotlin/Android printing bridge prototype for verified Milestone 1 work: profiles, print core, ESC/POS and TSPL generation, fake/TCP transports, simulator parsing, virtual previews, and a Material 3 test lab.

## Windows Commands

Build:

```powershell
& "C:\Users\Alex\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1\bin\gradle.bat" assembleDebug
```

Tests:

```powershell
& "C:\Users\Alex\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1\bin\gradle.bat" test
```

Install debug APK:

```powershell
& "C:\Users\Alex\AppData\Local\Android\Sdk\platform-tools\adb.exe" install app\build\outputs\apk\debug\app-debug.apk
```

Start simulator:

```powershell
& "C:\Users\Alex\.gradle\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\gradle-9.7.1\bin\gradle.bat" :simulator-app:run --args="127.0.0.1 9100"
```

Connect TCP:

```text
127.0.0.1:9100
```

First test print:

```text
Open PrintBridge, select a generic profile, generate TEST RECEIPT or TEST LABEL, then use FAKE PRINT or configure TCP in later UI work.
```
