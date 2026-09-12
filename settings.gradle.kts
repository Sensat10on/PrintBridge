pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Allows Gradle to provision the pinned JDK toolchain (see build.gradle.kts)
    // automatically instead of relying on whatever JDK happens to be on the machine.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PrintBridge"
include(":app", ":print-core", ":printer-drivers", ":printer-transport", ":printer-bluetooth", ":printer-usb", ":simulator-core", ":simulator-app", ":windows-print-bridge")
