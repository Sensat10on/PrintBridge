plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
    `maven-publish`
}

dependencies {
    // PrinterDriver signatures use core types, so consumers need them on the compile classpath.
    api(project(":print-core"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
