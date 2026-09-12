plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
    `maven-publish`
}

dependencies {
    // ParseResult and SimulatorJob expose core types (PrinterProtocol, MonoBitmap).
    api(project(":print-core"))
    testImplementation(project(":printer-drivers"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
