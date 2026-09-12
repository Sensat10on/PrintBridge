import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing material is never committed. Credentials are read from, in order of
// precedence: -P Gradle properties, environment variables, then a local
// keystore.properties file at the repository root.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) keystorePropertiesFile.inputStream().use { load(it) }
}

fun signingValue(propertyName: String, envName: String, key: String): String? =
    (project.findProperty(propertyName) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("printbridge.storeFile", "PRINTBRIDGE_KEYSTORE_FILE", "storeFile")
val releaseStorePassword = signingValue("printbridge.storePassword", "PRINTBRIDGE_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("printbridge.keyAlias", "PRINTBRIDGE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("printbridge.keyPassword", "PRINTBRIDGE_KEY_PASSWORD", "keyPassword")

val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

// Relative keystore paths are resolved against the repository root.
val releaseStoreFileResolved: File? = releaseStoreFile?.let { configured ->
    val candidate = File(configured)
    if (candidate.isAbsolute) candidate else rootProject.file(configured)
}

// Fails the build instead of silently producing an unsigned artifact. Enabled in CI and
// by release tooling via -Pprintbridge.requireReleaseSigning=true.
val requireReleaseSigning = providers.gradleProperty("printbridge.requireReleaseSigning")
    .map { it.toBoolean() }
    .getOrElse(false)

// Single source of truth for the shipped version; override with -Pprintbridge.versionCode=...
val appVersionCode = providers.gradleProperty("printbridge.versionCode")
    .map { it.toInt() }
    .getOrElse(1)
val appVersionName = providers.gradleProperty("printbridge.versionName")
    .getOrElse("0.1.0")

android {
    namespace = "com.printbridge.app"
    compileSdk = 36

    // Version comes from gradle.properties (or a -P override); see docs/RELEASE_PROCESS.md.
    defaultConfig {
        applicationId = "com.printbridge.app"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFileResolved
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // minSdk is 26, so the APK Signature Scheme v2/v3 blocks are enough; the
                // legacy v1 (JAR) signature is not produced.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Keeps the dependency metadata block out of the artifacts so that APK hashes are
    // stable between builds of the same revision.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// Release gate: refuse to build a distributable artifact that cannot be signed.
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        if (requireReleaseSigning && !hasReleaseSigning) {
            throw GradleException(
                "Release signing is required (printbridge.requireReleaseSigning=true) but no credentials were found. " +
                    "Provide keystore.properties at the repository root, or set the printbridge.storeFile / " +
                    "printbridge.storePassword / printbridge.keyAlias / printbridge.keyPassword Gradle properties " +
                    "(PRINTBRIDGE_* environment variables also work). See docs/RELEASE_PROCESS.md."
            )
        }
        if (!hasReleaseSigning) {
            logger.lifecycle(
                "WARNING: release signing credentials are absent; the release artifact will be UNSIGNED and " +
                    "cannot be distributed. See docs/RELEASE_PROCESS.md."
            )
        }
    }
}

// Robolectric cannot instrument bytecode produced by a JDK newer than the one it
// supports, so compile and unit-test JVMs are pinned to the provisioned toolchain
// declared in the root build.gradle.kts.
kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":print-core"))
    implementation(project(":printer-drivers"))
    implementation(project(":printer-transport"))
    implementation(project(":printer-bluetooth"))
    implementation(project(":printer-usb"))
    implementation(project(":simulator-core"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.ui:ui:1.9.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.9.1")
    implementation("androidx.core:core-ktx:1.17.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    debugImplementation("androidx.compose.ui:ui-tooling:1.9.1")
}
