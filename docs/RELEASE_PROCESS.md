# Release Process

Everything a release needs: a pinned toolchain, a signed artifact, a reproducible build, and a
verification step. The Android application id is `com.printbridge.app`.

## 1. Toolchain

The build does not use whatever JDK happens to be installed. `build.gradle.kts` pins the JDK
toolchain and `settings.gradle.kts` enables the foojay resolver, so Gradle provisions the JDK
itself (into `~/.gradle/jdks`) on first use.

| Component | Value |
|---|---|
| Toolchain / test JVM | JDK 21 (`JavaLanguageVersion.of(21)`) |
| Gradle | 9.7.1 (wrapper) |
| AGP / Kotlin | 9.4.0 / 2.2.20 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| Java / Kotlin target | 21 |

Do not raise the toolchain above 21 without re-checking the Robolectric suite: Robolectric
instruments bytecode with ASM and fails with `Unsupported class file major version` on JDKs it
does not know yet.

## 2. Version

`gradle.properties` is the single source of truth:

```properties
printbridge.versionCode=2
printbridge.versionName=0.2.0
```

Bump both for every distributed build (`versionCode` must increase monotonically). A hotfix can
override them without editing the file:

```powershell
.\gradlew.bat :app:assembleRelease "-Pprintbridge.versionCode=3" "-Pprintbridge.versionName=0.2.1"
```

## 3. Signing

Release signing material is never committed (`keystore.properties`, `*.jks`, `*.keystore` are
git-ignored). `app/build.gradle.kts` reads credentials from the first source that provides them:

1. Gradle properties: `-Pprintbridge.storeFile`, `-Pprintbridge.storePassword`,
   `-Pprintbridge.keyAlias`, `-Pprintbridge.keyPassword`
2. Environment variables: `PRINTBRIDGE_KEYSTORE_FILE`, `PRINTBRIDGE_KEYSTORE_PASSWORD`,
   `PRINTBRIDGE_KEY_ALIAS`, `PRINTBRIDGE_KEY_PASSWORD`
3. `keystore.properties` at the repository root

### Local verification key

```powershell
.\tools\create-release-keystore.ps1
```

This creates `release-keystore.jks` plus `keystore.properties` for local checks only. A key
generated this way must **not** be used to ship to users.

### Production key

Generate on a trusted machine, back up the keystore and its passwords separately, and hand them
to CI through the `PRINTBRIDGE_*` environment variables (GitHub Actions secrets). Losing the key
means the app can never be updated in place again.

### Release gate

```powershell
.\gradlew.bat :app:assembleRelease "-Pprintbridge.requireReleaseSigning=true"
```

With `printbridge.requireReleaseSigning=true` the build fails instead of silently producing an
unsigned artifact. Without it, the build succeeds but prints a warning; the artifact is then
`app-release-unsigned.apk` and cannot be distributed.

## 4. Release command sequence

Two product flavours exist. `paid` is the shippable build; `full` is the internal test build with
every feature unlocked (see `docs/KNOWN_LIMITATIONS.md`).

| Build | Command | Output | Package |
|---|---|---|---|
| Release (ships) | `:app:assemblePaidRelease` | `app/build/outputs/apk/paid/release/app-paid-release.apk` | `com.printbridge.app` |
| Release for testing | `:app:assemblePaidDebug` | `app/build/outputs/apk/paid/debug/app-paid-debug.apk` | `com.printbridge.app` |
| Full test build | `:app:assembleFullDebug` | `app/build/outputs/apk/full/debug/app-full-debug.apk` | `com.printbridge.app.full`, version `…-full` |

Both flavours install side by side: the test build carries the `.full` application id suffix.

```powershell
cd <repo>
.\gradlew.bat clean test assemblePaidDebug assemblePaidRelease assembleFullDebug lintDebug `
    "-Pprintbridge.requireReleaseSigning=true"
```

Expected result: `BUILD SUCCESSFUL`, every unit test green in both flavours (`:app:testPaidDebugUnitTest`
and `:app:testFullDebugUnitTest`), `lintDebug` with zero errors.

Install both on a device:

```powershell
adb install -r app\build\outputs\apk\paid\release\app-paid-release.apk
adb install -r app\build\outputs\apk\full\debug\app-full-debug.apk
# The test build keeps the namespace activity name, so launch it with the full component:
adb shell am start -n com.printbridge.app.full/com.printbridge.app.MainActivity
```

## 5. Artifact verification

```powershell
# Signature (v2/v3 schemes; the APK must be signed, not "unsigned")
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs --verbose `
    app\build\outputs\apk\release\app-release.apk

# Version and permissions actually packaged
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\23.0\bin\apkanalyzer.bat" manifest print `
    app\build\outputs\apk\release\app-release.apk

# Hash for the release notes
Get-FileHash app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
```

Release build settings: `isMinifyEnabled = true`, `isShrinkResources = true`,
`dependenciesInfo` disabled (removes the non-deterministic dependency metadata block from the
APK), so two builds of the same revision with the same JDK and signing key produce the same
bytes. Without minification the release APK is roughly 7.5 MB; with R8 it is roughly 1.2 MB.

## 6. Device verification (not automatable here)

Before publishing, install the signed APK on real hardware and check, at minimum:

- clean install, upgrade over the previous version, and process death/restart;
- launch, no `FATAL EXCEPTION` in `logcat`;
- printing over USB, Bluetooth SPP and raw TCP against real printers, including denied
  permission, unplug/reconnect, timeout, partial write and re-send;
- layout on a small phone and a large tablet.

`docs/KNOWN_LIMITATIONS.md` lists what software tests deliberately do not cover.

## 7. Continuous integration

`.github/workflows/android.yml` runs on push to `main` and on pull requests: unit tests,
`assembleDebug`, `lintDebug`, and it records the APK SHA-256. It pins JDK 21 and accepts the
Android SDK licenses automatically, so **no secrets are required** for this workflow to be useful.

`.github/workflows/release.yml` runs on a `v*` tag (or manually from the Actions tab) and does two
things in separate jobs:

1. builds the release APK **signed with the keystore from repository secrets**, verifies it with
   `apksigner`, and attaches it to a GitHub Release;
2. publishes the library modules to GitHub Packages.

## 8. Connecting your GitHub account

### 8.1. Push the repository

The checkout already has `origin = https://github.com/Sensat10on/PrintBridge.git`. Authenticate
once, then push:

```powershell
# Option A: GitHub CLI (already installed here)
gh auth login                      # choose HTTPS, login with a browser
gh auth setup-git                  # makes git use the gh credentials

# Option B: Personal Access Token
# Create a token with the "repo" scope at https://github.com/settings/tokens
# and use it as the password when git asks.

git push origin main
```

After the push, `Actions` on GitHub shows the CI workflow running. A green run means the same
checks that pass locally also pass on a clean machine.

### 8.2. Add signing secrets

`Settings -> Secrets and variables -> Actions -> New repository secret`:

| Secret | Value |
|---|---|
| `PRINTBRIDGE_KEYSTORE_BASE64` | base64 of the `.jks` file (command below) |
| `PRINTBRIDGE_KEYSTORE_PASSWORD` | keystore password |
| `PRINTBRIDGE_KEY_ALIAS` | key alias (for example `printbridge`) |
| `PRINTBRIDGE_KEY_PASSWORD` | key password |

```powershell
# Linux/macOS
base64 -w0 release-keystore.jks > keystore.b64
# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release-keystore.jks")) | Set-Content keystore.b64 -NoNewline
```

Paste the contents of `keystore.b64`, then delete that file. Use the **production** keystore here,
not the local one from `tools/create-release-keystore.ps1`: the signing key determines the app
identity, and losing it means the app can never be updated in place again. Back up the keystore
and its passwords somewhere safe outside the repository.

### 8.3. Cut a release

```powershell
git tag v0.2.0
git push origin v0.2.0
```

The tag version must match `printbridge.versionName` in `gradle.properties`; `versionCode` is read
from that file unless the workflow is started manually with explicit values. The job summary shows
the APK SHA-256 and the verified signature schemes, and the APK appears under
`Releases` as a download.

### 8.4. Repository settings worth enabling

- `Settings -> Actions -> General`: keep "Workflow permissions" at read-only for the default token;
  the release workflow already requests `contents: write` and `packages: write` explicitly.
- `Settings -> Code security`: enable Dependabot alerts to be notified about the dependency
  versions (this project pins versions inline, so alerts are the main signal).
- Optional: protect `main` and require the `Android CI` job to pass before merging.

## 9. Publishing library modules (GitHub Packages)

`print-core`, `printer-drivers`, `printer-transport` and `simulator-core` apply `maven-publish` and
are published as `com.printbridge:<module>:<version>`. The Android modules are intentionally not
published: they are only consumed by the app in this repository.

Check the coordinates locally before publishing:

```powershell
.\gradlew.bat publishToMavenLocal
# ~/.m2/repository/com/printbridge/print-core/0.2.0/{jar,pom,module,sources.jar}
```

Publish to GitHub Packages:

```powershell
$env:GITHUB_ACTOR = "Sensat10on"
$env:GITHUB_TOKEN = "<token with write:packages>"
.\gradlew.bat publish "-Pprintbridge.versionName=0.2.0"
```

The workflow does the same on a tag, using the automatic `GITHUB_TOKEN`. The target repository can
be overridden with `-Pprintbridge.github.repository=https://maven.pkg.github.com/<owner>/<repo>`.

### Consuming the packages

GitHub Packages requires authentication even for downloads, so a consumer needs a token with the
`read:packages` scope:

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/Sensat10on/PrintBridge")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("com.printbridge:print-core:0.2.0")
    implementation("com.printbridge:printer-drivers:0.2.0")
    implementation("com.printbridge:printer-transport:0.2.0")
    implementation("com.printbridge:simulator-core:0.2.0")
}
```

Add the consumer's own `printbridge.versionName` bump to the release checklist whenever the
published API changes.
