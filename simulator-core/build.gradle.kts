plugins { id("org.jetbrains.kotlin.jvm") }

dependencies {
    implementation(project(":print-core"))
    testImplementation(project(":printer-drivers"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
