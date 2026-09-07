plugins { id("org.jetbrains.kotlin.jvm") }

dependencies {
    implementation(project(":print-core"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
