pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
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
